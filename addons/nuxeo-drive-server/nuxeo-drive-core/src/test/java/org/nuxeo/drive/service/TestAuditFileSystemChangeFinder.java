/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.drive.service.impl.AuditChangeFinder;
import org.nuxeo.drive.service.impl.RootDefinitionsHelper;
import org.nuxeo.ecm.collections.api.CollectionManager;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.LifeCycleConstants;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.impl.blob.StringBlob;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.EventServiceAdmin;
import org.nuxeo.ecm.core.event.impl.EventListenerDescriptor;
import org.nuxeo.ecm.core.event.impl.EventListenerList;
import org.nuxeo.ecm.core.persistence.PersistenceProvider.RunVoid;
import org.nuxeo.ecm.core.test.RepositorySettings;
import org.nuxeo.ecm.core.versioning.VersioningService;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.core.work.api.WorkQueueDescriptor;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.audit.AuditFeature;
import org.nuxeo.ecm.platform.audit.service.DefaultAuditBackend;
import org.nuxeo.ecm.platform.audit.service.NXAuditEventsService;
import org.nuxeo.ecm.platform.usermanager.NuxeoPrincipalImpl;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.google.inject.Inject;

/**
 * Test the {@link AuditChangeFinder}.
 */
@RunWith(FeaturesRunner.class)
@Features(AuditFeature.class)
// We handle transaction start and commit manually to make it possible to have
// several consecutive transactions in a test method
@Deploy({ "org.nuxeo.ecm.platform.userworkspace.types", "org.nuxeo.ecm.platform.userworkspace.core",
        "org.nuxeo.drive.core", "org.nuxeo.ecm.platform.collections.core", "org.nuxeo.ecm.platform.web.common",
        "org.nuxeo.ecm.platform.query.api" })
@LocalDeploy("org.nuxeo.drive.core:OSGI-INF/test-nuxeodrive-types-contrib.xml")
public class TestAuditFileSystemChangeFinder {

    private static final Log log = LogFactory.getLog(TestAuditFileSystemChangeFinder.class);

    @Inject
    protected CoreSession session;

    @Inject
    protected RepositorySettings repository;

    @Inject
    protected DirectoryService directoryService;

    @Inject
    protected EventService eventService;

    @Inject
    protected NuxeoDriveManager nuxeoDriveManager;

    @Inject
    protected EventServiceAdmin eventServiceAdmin;

    @Inject
    protected WorkManager workManager;

    @Inject
    protected CollectionManager collectionManager;

    protected long lastEventLogId;

    protected String lastSyncActiveRootDefinitions;

    protected DocumentModel folder1;

    protected DocumentModel folder2;

    protected DocumentModel folder3;

    protected CoreSession user1Session;

    @Before
    public void init() throws Exception {
        // Disable asynchronous event listeners except for the audit logger
        EventListenerList eventListeners = eventServiceAdmin.getListenerList();
        List<EventListenerDescriptor> postCommitListenerDescs = eventListeners.getAsyncPostCommitListenersDescriptors();
        for (EventListenerDescriptor postCommitListenerDesc : postCommitListenerDescs) {
            String postCommitListenerDescName = postCommitListenerDesc.getName();
            if (!"auditLoggerListener".equals(postCommitListenerDescName)) {
                eventServiceAdmin.setListenerEnabledFlag(postCommitListenerDescName, false);
            }
        }
        // Disable work queues except for the audit one
        List<String> workQueueIds = workManager.getWorkQueueIds();
        for (String workQueueId : workQueueIds) {
            if (!"audit".equals(workQueueId)) {
                WorkQueueDescriptor desc = workManager.getWorkQueueDescriptor(workQueueId);
                desc.queuing = Boolean.FALSE;
            }
        }
        // Enable deletion listener because the tear down disables it
        eventServiceAdmin.setListenerEnabledFlag("nuxeoDriveFileSystemDeletionListener", true);

        lastEventLogId = nuxeoDriveManager.getChangeFinder().getUpperBound();
        lastSyncActiveRootDefinitions = "";
        Framework.getProperties().put("org.nuxeo.drive.document.change.limit", "10");

        // Create test users
        Session userDir = directoryService.getDirectory("userDirectory").getSession();
        try {
            Map<String, Object> user1 = new HashMap<String, Object>();
            user1.put("username", "user1");
            user1.put("groups", Arrays.asList(new String[] { "members" }));
            userDir.createEntry(user1);
        } finally {
            userDir.close();
        }
        user1Session = repository.openSessionAs("user1");

        commitAndWaitForAsyncCompletion();

        folder1 = session.createDocument(session.createDocumentModel("/", "folder1", "Folder"));
        folder2 = session.createDocument(session.createDocumentModel("/", "folder2", "Folder"));
        folder3 = session.createDocument(session.createDocumentModel("/", "folder3", "Folder"));
        setPermissions(folder1, new ACE("user1", SecurityConstants.READ_WRITE));
        setPermissions(folder2, new ACE("user1", SecurityConstants.READ_WRITE));

        commitAndWaitForAsyncCompletion();
    }

    @After
    public void tearDown() throws ClientException {

        if (user1Session != null) {
            user1Session.close();
        }
        Session usersDir = directoryService.getDirectory("userDirectory").getSession();
        try {
            usersDir.deleteEntry("user1");
        } finally {
            usersDir.close();
        }

        // Disable deletion listener for the repository cleanup phase done in
        // CoreFeature#afterTeardown to avoid exception due to no active
        // transaction in FileSystemItemManagerImpl#getSession
        eventServiceAdmin.setListenerEnabledFlag("nuxeoDriveFileSystemDeletionListener", false);

        // Clean up audit log
        cleanUpAuditLog();
    }

    @Test
    public void testFindChanges() throws Exception {
        List<FileSystemItemChange> changes;
        FileSystemItemChange change;
        DocumentModel doc1;
        DocumentModel doc2;
        DocumentModel doc3;
        DocumentModel docToCopy;
        DocumentModel copiedDoc;
        DocumentModel docToVersion;

        commitAndWaitForAsyncCompletion();
        try {
            // No sync roots
            changes = getChanges();
            assertNotNull(changes);
            assertTrue(changes.isEmpty());

            log.trace("Sync roots for Administrator");
            nuxeoDriveManager.registerSynchronizationRoot(session.getPrincipal(), folder1, session);
            nuxeoDriveManager.registerSynchronizationRoot(session.getPrincipal(), folder2, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Get changes for Administrator
            changes = getChanges();
            // Root registration events
            assertEquals(2, changes.size());

            log.trace("Create 3 documents, only 2 in sync roots");
            doc1 = session.createDocumentModel("/folder1", "doc1", "File");
            doc1.setPropertyValue("file:content", new StringBlob("The content of file 1."));
            doc1 = session.createDocument(doc1);
            doc2 = session.createDocumentModel("/folder2", "doc2", "File");
            doc2.setPropertyValue("file:content", new StringBlob("The content of file 2."));
            doc2 = session.createDocument(doc2);
            doc3 = session.createDocumentModel("/folder3", "doc3", "File");
            doc3.setPropertyValue("file:content", new StringBlob("The content of file 3."));
            doc3 = session.createDocument(doc3);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(2, changes.size());
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("documentCreated", change.getEventId());
            assertEquals(doc2.getId(), change.getDocUuid());
            change = changes.get(1);
            assertEquals("test", change.getRepositoryId());
            assertEquals("documentCreated", change.getEventId());
            assertEquals(doc1.getId(), change.getDocUuid());

            // No changes since last successful sync
            changes = getChanges();
            assertTrue(changes.isEmpty());

            log.trace("Update both synchronized documents and unsynchronize a root");
            doc1.setPropertyValue("file:content", new StringBlob("The content of file 1, updated."));
            session.saveDocument(doc1);
            doc2.setPropertyValue("file:content", new StringBlob("The content of file 2, updated."));
            session.saveDocument(doc2);
            nuxeoDriveManager.unregisterSynchronizationRoot(session.getPrincipal(), folder2, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(2, changes.size());
            // The root unregistration is mapped to a fake deletion from the
            // client's point of view
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("deleted", change.getEventId());
            assertEquals(folder2.getId(), change.getDocUuid());

            change = changes.get(1);
            assertEquals("test", change.getRepositoryId());
            assertEquals("documentModified", change.getEventId());
            assertEquals(doc1.getId(), change.getDocUuid());

            log.trace("Delete a document with a lifecycle transition (trash)");
            session.followTransition(doc1.getRef(), "delete");
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("deleted", change.getEventId());
            assertEquals(doc1.getId(), change.getDocUuid());
            assertEquals("test#" + doc1.getId(), change.getFileSystemItemId());

            log.trace("Restore a deleted document and move a document in a newly synchronized root");
            session.followTransition(doc1.getRef(), "undelete");
            session.move(doc3.getRef(), folder2.getRef(), null);
            nuxeoDriveManager.registerSynchronizationRoot(session.getPrincipal(), folder2, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(3, changes.size());
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("rootRegistered", change.getEventId());
            assertEquals(folder2.getId(), change.getDocUuid());
            assertEquals("defaultSyncRootFolderItemFactory#test#" + folder2.getId(), change.getFileSystemItemId());
            change = changes.get(1);
            assertEquals("test", change.getRepositoryId());
            assertEquals("documentMoved", change.getEventId());
            assertEquals(doc3.getId(), change.getDocUuid());
            change = changes.get(2);
            assertEquals("test", change.getRepositoryId());
            assertEquals("lifecycle_transition_event", change.getEventId());
            assertEquals(doc1.getId(), change.getDocUuid());

            log.trace("Physical deletion without triggering the delete transition first");
            session.removeDocument(doc3.getRef());
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("deleted", change.getEventId());
            assertEquals(doc3.getId(), change.getDocUuid());
            assertEquals("test#" + doc3.getId(), change.getFileSystemItemId());

            log.trace("Create a doc and copy it from a sync root to another one");
            docToCopy = session.createDocumentModel("/folder1", "docToCopy", "File");
            docToCopy.setPropertyValue("file:content", new StringBlob("The content of file to copy."));
            docToCopy = session.createDocument(docToCopy);
            copiedDoc = session.copy(docToCopy.getRef(), folder2.getRef(), null);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(2, changes.size());
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("documentCreatedByCopy", change.getEventId());
            assertEquals(copiedDoc.getId(), change.getDocUuid());
            assertEquals("defaultFileSystemItemFactory#test#" + copiedDoc.getId(), change.getFileSystemItemId());
            assertEquals("docToCopy", change.getFileSystemItemName());

            change = changes.get(1);
            assertEquals("test", change.getRepositoryId());
            assertEquals("documentCreated", change.getEventId());
            assertEquals(docToCopy.getId(), change.getDocUuid());
            assertEquals("defaultFileSystemItemFactory#test#" + docToCopy.getId(), change.getFileSystemItemId());
            assertEquals("docToCopy", change.getFileSystemItemName());

            log.trace("Remove file from a document, mapped to a fake deletion from the client's point of view");
            doc1.setPropertyValue("file:content", null);
            session.saveDocument(doc1);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("deleted", change.getEventId());
            assertEquals(doc1.getId(), change.getDocUuid());

            log.trace("Move a doc from a sync root to a non synchronized folder");
            session.move(copiedDoc.getRef(), folder3.getRef(), null);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("test", change.getRepositoryId());
            assertEquals("deleted", change.getEventId());
            assertEquals(copiedDoc.getId(), change.getDocUuid());

            log.trace("Create a doc, create a version of it, update doc and restore the version");
            docToVersion = session.createDocumentModel("/folder1", "docToVersion", "File");
            docToVersion.setPropertyValue("file:content", new StringBlob("The content of file to version."));
            docToVersion = session.createDocument(docToVersion);
            docToVersion.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MAJOR);
            session.saveDocument(docToVersion);
            docToVersion.setPropertyValue("file:content", new StringBlob("Updated content of the versioned file."));
            session.saveDocument(docToVersion);
            List<DocumentModel> versions = session.getVersions(docToVersion.getRef());
            assertEquals(1, versions.size());
            DocumentModel version = versions.get(0);
            session.restoreToVersion(docToVersion.getRef(), version.getRef());
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges();
            assertEquals(4, changes.size());

            change = changes.get(0);
            assertEquals("documentRestored", change.getEventId());
            assertEquals(docToVersion.getId(), change.getDocUuid());

            change = changes.get(1);
            assertEquals("documentModified", change.getEventId());
            assertEquals(docToVersion.getId(), change.getDocUuid());

            change = changes.get(2);
            assertEquals("documentModified", change.getEventId());
            assertEquals(docToVersion.getId(), change.getDocUuid());

            change = changes.get(3);
            assertEquals("documentCreated", change.getEventId());
            assertEquals(docToVersion.getId(), change.getDocUuid());

            log.trace("Too many changes");
            session.followTransition(doc1.getRef(), "delete");
            session.followTransition(doc2.getRef(), "delete");
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        Framework.getProperties().put("org.nuxeo.drive.document.change.limit", "1");
        FileSystemChangeSummary changeSummary = getChangeSummary(session.getPrincipal());
        assertEquals(true, changeSummary.getHasTooManyChanges());

    }

    @Test
    public void testFindSecurityChanges() throws Exception {
        List<FileSystemItemChange> changes;
        FileSystemItemChange change;
        DocumentModel subFolder;

        try {
            // No sync roots
            changes = getChanges();
            assertTrue(changes.isEmpty());

            // Create a folder in a sync root
            subFolder = user1Session.createDocumentModel("/folder1", "subFolder", "Folder");
            subFolder = user1Session.createDocument(subFolder);

            // Sync roots for user1
            nuxeoDriveManager.registerSynchronizationRoot(user1Session.getPrincipal(), folder1, user1Session);
            nuxeoDriveManager.registerSynchronizationRoot(user1Session.getPrincipal(), folder2, user1Session);
        } finally {
            commitAndWaitForAsyncCompletion(user1Session);
        }

        try {
            // Get changes for user1
            changes = getChanges(user1Session.getPrincipal());
            // Folder creation and sync root registration events
            assertEquals(3, changes.size());

            // Permission changes: deny Read
            // Deny Read to user1 on a regular doc
            setPermissions(subFolder, new ACE(SecurityConstants.ADMINISTRATOR, SecurityConstants.EVERYTHING), ACE.BLOCK);
            // Deny Read to user1 on a sync root
            setPermissions(folder2, new ACE(SecurityConstants.ADMINISTRATOR, SecurityConstants.EVERYTHING), ACE.BLOCK);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changes = getChanges(user1Session.getPrincipal());
            assertEquals(2, changes.size());

            change = changes.get(0);
            assertEquals("securityUpdated", change.getEventId());
            assertEquals(folder2.getId(), change.getDocUuid());
            assertEquals("test#" + folder2.getId(), change.getFileSystemItemId());
            assertEquals("folder2", change.getFileSystemItemName());
            // Not adaptable as a FileSystemItem since no Read permission
            assertNull(change.getFileSystemItem());

            change = changes.get(1);
            assertEquals("securityUpdated", change.getEventId());
            assertEquals(subFolder.getId(), change.getDocUuid());
            assertEquals("test#" + subFolder.getId(), change.getFileSystemItemId());
            assertEquals("subFolder", change.getFileSystemItemName());
            // Not adaptable as a FileSystemItem since no Read permission
            assertNull(change.getFileSystemItem());

            // Permission changes: grant Read
            // Grant Read to user1 on a regular doc
            setPermissions(subFolder, new ACE("user1", SecurityConstants.READ));
            // Grant Read to user1 on a sync root
            setPermissions(folder2, new ACE("user1", SecurityConstants.READ));
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        changes = getChanges(user1Session.getPrincipal());
        assertEquals(2, changes.size());

        change = changes.get(0);
        assertEquals("securityUpdated", change.getEventId());
        assertEquals(folder2.getId(), change.getDocUuid());
        assertEquals("defaultSyncRootFolderItemFactory#test#" + folder2.getId(), change.getFileSystemItemId());
        assertEquals("folder2", change.getFileSystemItemName());
        // Adaptable as a FileSystemItem since Read permission
        assertNotNull(change.getFileSystemItem());

        change = changes.get(1);
        assertEquals("securityUpdated", change.getEventId());
        assertEquals(subFolder.getId(), change.getDocUuid());
        assertEquals("defaultFileSystemItemFactory#test#" + subFolder.getId(), change.getFileSystemItemId());
        assertEquals("subFolder", change.getFileSystemItemName());
        // Adaptable as a FileSystemItem since Read permission
        assertNotNull(change.getFileSystemItem());

    }

    @Test
    public void testGetChangeSummary() throws Exception {
        FileSystemChangeSummary changeSummary;
        Principal admin = new NuxeoPrincipalImpl("Administrator");
        DocumentModel doc1;
        DocumentModel doc2;

        try {
            // No sync roots => shouldn't find any changes
            changeSummary = getChangeSummary(admin);
            assertNotNull(changeSummary);
            assertTrue(changeSummary.getFileSystemChanges().isEmpty());
            assertEquals(Boolean.FALSE, changeSummary.getHasTooManyChanges());

            // Register sync roots => should find changes: the newly
            // synchronized root folders as they are updated by the
            // synchronization
            // registration process
            nuxeoDriveManager.registerSynchronizationRoot(admin, folder1, session);
            nuxeoDriveManager.registerSynchronizationRoot(admin, folder2, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changeSummary = getChangeSummary(admin);
            assertEquals(2, changeSummary.getFileSystemChanges().size());
            assertEquals(Boolean.FALSE, changeSummary.getHasTooManyChanges());
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        // Create 3 documents, only 2 in sync roots => should find 2 changes
        try {
            doc1 = session.createDocumentModel("/folder1", "doc1", "File");
            doc1.setPropertyValue("file:content", new StringBlob("The content of file 1."));
            doc1 = session.createDocument(doc1);
            doc2 = session.createDocumentModel("/folder2", "doc2", "File");
            doc2.setPropertyValue("file:content", new StringBlob("The content of file 2."));
            doc2 = session.createDocument(doc2);
            session.createDocument(session.createDocumentModel("/folder3", "doc3", "File"));
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changeSummary = getChangeSummary(admin);

            List<FileSystemItemChange> changes = changeSummary.getFileSystemChanges();
            assertEquals(2, changes.size());
            FileSystemItemChange docChange = changes.get(0);
            assertEquals("test", docChange.getRepositoryId());
            assertEquals("documentCreated", docChange.getEventId());
            assertEquals("project", session.getDocument(new IdRef(docChange.getDocUuid())).getCurrentLifeCycleState());
            assertEquals(doc2.getId(), docChange.getDocUuid());
            docChange = changes.get(1);
            assertEquals("test", docChange.getRepositoryId());
            assertEquals("documentCreated", docChange.getEventId());
            assertEquals("project", session.getDocument(new IdRef(docChange.getDocUuid())).getCurrentLifeCycleState());
            assertEquals(doc1.getId(), docChange.getDocUuid());

            assertEquals(Boolean.FALSE, changeSummary.getHasTooManyChanges());

            // Create a document that should not be synchronized because not
            // adaptable as a FileSystemItem (not Folderish nor a BlobHolder
            // with a
            // blob) => should not be considered as a change
            session.createDocument(session.createDocumentModel("/folder1", "notSynchronizableDoc", "NotSynchronizable"));
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changeSummary = getChangeSummary(admin);
            assertTrue(changeSummary.getFileSystemChanges().isEmpty());
            assertEquals(Boolean.FALSE, changeSummary.getHasTooManyChanges());

            // Create 2 documents in the same sync root: "/folder1" and 1
            // document
            // in another sync root => should find 2 changes for "/folder1"
            DocumentModel doc3 = session.createDocumentModel("/folder1", "doc3", "File");
            doc3.setPropertyValue("file:content", new StringBlob("The content of file 3."));
            doc3 = session.createDocument(doc3);
            DocumentModel doc4 = session.createDocumentModel("/folder1", "doc4", "File");
            doc4.setPropertyValue("file:content", new StringBlob("The content of file 4."));
            doc4 = session.createDocument(doc4);
            DocumentModel doc5 = session.createDocumentModel("/folder2", "doc5", "File");
            doc5.setPropertyValue("file:content", new StringBlob("The content of file 5."));
            doc5 = session.createDocument(doc5);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            changeSummary = getChangeSummary(admin);
            assertEquals(Boolean.FALSE, changeSummary.getHasTooManyChanges());
            assertEquals(3, changeSummary.getFileSystemChanges().size());

            // No changes since last successful sync
            changeSummary = getChangeSummary(admin);
            assertTrue(changeSummary.getFileSystemChanges().isEmpty());
            assertEquals(Boolean.FALSE, changeSummary.getHasTooManyChanges());

            // Test too many changes
            session.followTransition(doc1.getRef(), "delete");
            session.followTransition(doc2.getRef(), "delete");
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        Framework.getProperties().put("org.nuxeo.drive.document.change.limit", "1");
        changeSummary = getChangeSummary(admin);
        assertTrue(changeSummary.getFileSystemChanges().isEmpty());
        assertEquals(Boolean.TRUE, changeSummary.getHasTooManyChanges());

    }

    @Test
    public void testGetChangeSummaryOnRootDocuments() throws Exception {
        Principal admin = new NuxeoPrincipalImpl("Administrator");
        Principal otherUser = new NuxeoPrincipalImpl("some-other-user");
        Set<IdRef> activeRootRefs;
        FileSystemChangeSummary changeSummary;
        List<FileSystemItemChange> changes;
        FileSystemItemChange fsItemChange;

        try {
            // No root registered by default: no changes
            activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(session);
            assertNotNull(activeRootRefs);
            assertTrue(activeRootRefs.isEmpty());

            changeSummary = getChangeSummary(admin);
            assertNotNull(changeSummary);
            assertTrue(changeSummary.getFileSystemChanges().isEmpty());
            assertEquals(Boolean.FALSE, changeSummary.getHasTooManyChanges());

            // Register a root for someone else
            nuxeoDriveManager.registerSynchronizationRoot(otherUser, folder1, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Administrator does not see any change
            activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(session);
            assertNotNull(activeRootRefs);
            assertTrue(activeRootRefs.isEmpty());

            changeSummary = getChangeSummary(admin);
            assertNotNull(changeSummary);
            assertTrue(changeSummary.getFileSystemChanges().isEmpty());
            assertFalse(changeSummary.getHasTooManyChanges());

            // Register a new sync root
            nuxeoDriveManager.registerSynchronizationRoot(admin, folder1, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(session);
            assertNotNull(activeRootRefs);
            assertEquals(1, activeRootRefs.size());
            assertEquals(folder1.getRef(), activeRootRefs.iterator().next());

            // The new sync root is detected in the change summary
            changeSummary = getChangeSummary(admin);
            assertNotNull(changeSummary);

            changes = changeSummary.getFileSystemChanges();
            assertEquals(1, changes.size());
            fsItemChange = changes.get(0);
            assertEquals("rootRegistered", fsItemChange.getEventId());
            assertEquals("defaultSyncRootFolderItemFactory#test#" + folder1.getId(),
                    fsItemChange.getFileSystemItem().getId());

            // Check that root unregistration is detected as a deletion
            nuxeoDriveManager.unregisterSynchronizationRoot(admin, folder1, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(session);
            assertNotNull(activeRootRefs);
            assertTrue(activeRootRefs.isEmpty());
            changeSummary = getChangeSummary(admin);
            changes = changeSummary.getFileSystemChanges();
            assertEquals(1, changes.size());
            fsItemChange = changes.get(0);
            assertEquals("deleted", fsItemChange.getEventId());
            assertEquals("test#" + folder1.getId(), fsItemChange.getFileSystemItemId());

            // Register back the root, it's activity is again detected by the
            // client
            nuxeoDriveManager.registerSynchronizationRoot(admin, folder1, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(session);
            assertNotNull(activeRootRefs);
            assertEquals(activeRootRefs.size(), 1);

            changeSummary = getChangeSummary(admin);
            changes = changeSummary.getFileSystemChanges();
            assertEquals(1, changes.size());
            fsItemChange = changes.get(0);
            assertEquals("rootRegistered", fsItemChange.getEventId());
            assertEquals("defaultSyncRootFolderItemFactory#test#" + folder1.getId(),
                    fsItemChange.getFileSystemItem().getId());

            // Test deletion of a root
            session.followTransition(folder1.getRef(), "delete");
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(session);
            assertNotNull(activeRootRefs);
            assertTrue(activeRootRefs.isEmpty());

            // The root is no longer active
            activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(session);
            assertNotNull(activeRootRefs);
            assertTrue(activeRootRefs.isEmpty());

            // The deletion of the root itself is mapped as filesystem
            // deletion event
            changeSummary = getChangeSummary(admin);
            changes = changeSummary.getFileSystemChanges();
            assertEquals(1, changes.size());
            fsItemChange = changes.get(0);
            assertEquals("deleted", fsItemChange.getEventId());
            assertEquals("test#" + folder1.getId(), fsItemChange.getFileSystemItemId());

        } finally {
            commitAndWaitForAsyncCompletion();
        }
    }

    @Test
    public void testSyncUnsyncRootsAsAnotherUser() throws Exception {
        Principal user1Principal = user1Session.getPrincipal();
        List<FileSystemItemChange> changes;
        FileSystemItemChange change;

        try {
            // No sync roots expected for user1
            changes = getChanges(user1Principal);
            assertNotNull(changes);
            assertTrue(changes.isEmpty());

            // Register sync roots for user1 as Administrator
            nuxeoDriveManager.registerSynchronizationRoot(user1Principal, folder1, session);
            nuxeoDriveManager.registerSynchronizationRoot(user1Principal, folder2, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // user1 should have 2 sync roots
            Set<IdRef> activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(user1Session);
            assertNotNull(activeRootRefs);
            assertEquals(2, activeRootRefs.size());
            assertTrue(activeRootRefs.contains(folder1.getRef()));
            assertTrue(activeRootRefs.contains(folder2.getRef()));

            // There should be 2 changes detected in the audit
            changes = getChanges(user1Principal);
            assertEquals(2, changes.size());

            change = changes.get(0);
            assertEquals("rootRegistered", change.getEventId());
            assertEquals(folder2.getId(), change.getDocUuid());
            assertEquals("defaultSyncRootFolderItemFactory#test#" + folder2.getId(), change.getFileSystemItemId());
            assertEquals("folder2", change.getFileSystemItemName());
            assertNotNull(change.getFileSystemItem());

            change = changes.get(1);
            assertEquals("rootRegistered", change.getEventId());
            assertEquals(folder1.getId(), change.getDocUuid());
            assertEquals("defaultSyncRootFolderItemFactory#test#" + folder1.getId(), change.getFileSystemItemId());
            assertEquals("folder1", change.getFileSystemItemName());
            assertNotNull(change.getFileSystemItem());

            // Unregister sync roots for user1 as Administrator
            nuxeoDriveManager.unregisterSynchronizationRoot(user1Principal, folder1, session);
            nuxeoDriveManager.unregisterSynchronizationRoot(user1Principal, folder2, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // user1 should have no sync roots
            Set<IdRef> activeRootRefs = nuxeoDriveManager.getSynchronizationRootReferences(user1Session);
            assertNotNull(activeRootRefs);
            assertTrue(activeRootRefs.isEmpty());

            // There should be 2 changes detected in the audit
            changes = getChanges(user1Principal);
            assertEquals(2, changes.size());

            change = changes.get(0);
            assertEquals("deleted", change.getEventId());
            assertEquals(folder2.getId(), change.getDocUuid());
            assertEquals("test#" + folder2.getId(), change.getFileSystemItemId());
            assertEquals("folder2", change.getFileSystemItemName());
            // Not adaptable as a FileSystemItem since unregistered
            assertNull(change.getFileSystemItem());

            change = changes.get(1);
            assertEquals("deleted", change.getEventId());
            assertEquals(folder1.getId(), change.getDocUuid());
            assertEquals("test#" + folder1.getId(), change.getFileSystemItemId());
            assertEquals("folder1", change.getFileSystemItemName());
            // Not adaptable as a FileSystemItem since unregistered
            assertNull(change.getFileSystemItem());
        } finally {
            commitAndWaitForAsyncCompletion();
        }
    }

    @Test
    public void testRegisterSyncRootAndUpdate() throws Exception {

        try {
            // Register folder1 as a sync root for Administrator
            nuxeoDriveManager.registerSynchronizationRoot(session.getPrincipal(), folder1, session);

            // Update folder1 title
            folder1.setPropertyValue("dc:title", "folder1 updated");
            session.saveDocument(folder1);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Check changes, expecting 3:
            // - documentModified
            // - rootRegistered
            // - documentCreated (at init)
            List<FileSystemItemChange> changes = getChanges();
            assertEquals(3, changes.size());
            FileSystemItemChange change = changes.get(0);
            assertEquals("documentModified", change.getEventId());
            change = changes.get(1);
            assertEquals("rootRegistered", change.getEventId());
            change = changes.get(2);
            assertEquals("documentCreated", change.getEventId());

            // Unregister folder1 as a sync root for Administrator
            nuxeoDriveManager.unregisterSynchronizationRoot(session.getPrincipal(), folder1, session);

            // Update folder1 title
            folder1.setPropertyValue("dc:title", "folder1 updated twice");
            session.saveDocument(folder1);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Check changes, expecting 1: deleted
            List<FileSystemItemChange> changes = getChanges();
            assertEquals(1, changes.size());
            FileSystemItemChange change = changes.get(0);
            assertEquals("deleted", change.getEventId());
        } finally {
            commitAndWaitForAsyncCompletion();
        }
    }

    @Test
    public void testMoveToOtherUsersSyncRoot() throws Exception {
        DocumentModel subFolder;
        List<FileSystemItemChange> changes;
        FileSystemItemChange change;
        try {
            // Create a subfolder in folder1 as Administrator
            subFolder = session.createDocument(session.createDocumentModel(folder1.getPathAsString(), "subFolder",
                    "Folder"));
            // Register folder1 as a sync root for user1
            nuxeoDriveManager.registerSynchronizationRoot(user1Session.getPrincipal(), folder1, user1Session);
            // Register folder2 as a sync root for Administrator
            nuxeoDriveManager.registerSynchronizationRoot(session.getPrincipal(), folder2, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Check changes for user1, expecting 3:
            // - rootRegistered for folder1
            // - documentCreated for subFolder1
            // - documentCreated for folder1 at init
            changes = getChanges(user1Session.getPrincipal());
            assertEquals(3, changes.size());
            change = changes.get(0);
            assertEquals("rootRegistered", change.getEventId());
            assertEquals(folder1.getId(), change.getDocUuid());
            change = changes.get(1);
            assertEquals("documentCreated", change.getEventId());
            assertEquals(subFolder.getId(), change.getDocUuid());
            change = changes.get(2);
            assertEquals("documentCreated", change.getEventId());
            assertEquals(folder1.getId(), change.getDocUuid());

            // As Administrator, move subfolder from folder1 (sync root for
            // user1) to folder2 (sync root for Administrator but not for
            // user1)
            session.move(subFolder.getRef(), folder2.getRef(), null);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Check changes for user1, expecting 1: deleted for subFolder
            changes = getChanges(user1Session.getPrincipal());
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("deleted", change.getEventId());
            assertEquals(subFolder.getId(), change.getDocUuid());
        } finally {
            commitAndWaitForAsyncCompletion();
        }
    }

    @Test
    public void testCollectionEvents() throws Exception {
        DocumentModel doc1;
        DocumentModel doc2;
        DocumentModel doc3;
        List<FileSystemItemChange> changes;
        FileSystemItemChange change;
        DocumentModel locallyEditedCollection;
        try {
            log.trace("Create 2 test docs and them to the 'Locally Edited' collection");
            doc1 = session.createDocumentModel(folder1.getPathAsString(), "doc1", "File");
            doc1.setPropertyValue("file:content", new StringBlob("File content."));
            doc1 = session.createDocument(doc1);
            doc2 = session.createDocumentModel(folder1.getPathAsString(), "doc2", "File");
            doc2.setPropertyValue("file:content", new StringBlob("File content."));
            doc2 = session.createDocument(doc2);
            nuxeoDriveManager.addToLocallyEditedCollection(session, doc1);
            nuxeoDriveManager.addToLocallyEditedCollection(session, doc2);
            DocumentModel userCollections = collectionManager.getUserDefaultCollections(folder1, session);
            DocumentRef locallyEditedCollectionRef = new PathRef(userCollections.getPath().toString(),
                    NuxeoDriveManager.LOCALLY_EDITED_COLLECTION_NAME);
            locallyEditedCollection = session.getDocument(locallyEditedCollectionRef);
            // Re-fetch documents to get rid of the disabled events in context
            // data
            doc1 = session.getDocument(doc1.getRef());
            doc2 = session.getDocument(doc2.getRef());
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Expecting 8 changes:
            // - addedToCollection for doc2
            // - documentModified for 'Locally Edited' collection
            // - rootRegistered for 'Locally Edited' collection
            // - addedToCollection for doc1
            // - documentModified for 'Locally Edited' collection
            // - documentCreated for 'Locally Edited' collection
            // - documentCreated for doc2
            // - documentCreated for doc1
            changes = getChanges(session.getPrincipal());
            List<SimpleFileSystemItemChange> expectedChanges = new ArrayList<>();
            expectedChanges.add(new SimpleFileSystemItemChange(doc2.getId(), "addedToCollection"));
            expectedChanges.add(new SimpleFileSystemItemChange(locallyEditedCollection.getId(), "documentModified"));
            expectedChanges.add(new SimpleFileSystemItemChange(locallyEditedCollection.getId(), "rootRegistered"));
            expectedChanges.add(new SimpleFileSystemItemChange(doc1.getId(), "addedToCollection"));
            expectedChanges.add(new SimpleFileSystemItemChange(locallyEditedCollection.getId(), "documentModified"));
            expectedChanges.add(new SimpleFileSystemItemChange(locallyEditedCollection.getId(), "documentCreated"));
            expectedChanges.add(new SimpleFileSystemItemChange(doc2.getId(), "documentCreated"));
            expectedChanges.add(new SimpleFileSystemItemChange(doc1.getId(), "documentCreated"));
            checkAuditChanges(expectedChanges, toSimpleFileSystemItemChanges(changes));

            log.trace("Update doc1 member of the 'Locally Edited' collection");
            doc1.setPropertyValue("file:content", new StringBlob("Updated file content."));
            session.saveDocument(doc1);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Expecting 1 change: documentModified for doc1
            changes = getChanges(session.getPrincipal());
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("documentModified", change.getEventId());
            assertEquals(doc1.getId(), change.getDocUuid());

            log.trace("Remove doc1 from the 'Locally Edited' collection, delete doc2 and add doc 3 to the collection");
            collectionManager.removeFromCollection(locallyEditedCollection, doc1, session);
            doc2.followTransition(LifeCycleConstants.DELETE_TRANSITION);
            doc3 = session.createDocumentModel(folder1.getPathAsString(), "doc3", "File");
            doc3.setPropertyValue("file:content", new StringBlob("File content."));
            doc3 = session.createDocument(doc3);
            collectionManager.addToCollection(locallyEditedCollection, doc3, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Expecting 6 changes:
            // - addedToCollection for doc3
            // - documentModified for 'Locally Edited' collection
            // - documentCreated for doc3
            // - deleted for doc2
            // - documentModified for 'Locally Edited' collection
            // - deleted for doc1
            changes = getChanges(session.getPrincipal());
            List<SimpleFileSystemItemChange> expectedChanges = new ArrayList<>();
            expectedChanges.add(new SimpleFileSystemItemChange(doc3.getId(), "addedToCollection"));
            expectedChanges.add(new SimpleFileSystemItemChange(locallyEditedCollection.getId(), "documentModified"));
            expectedChanges.add(new SimpleFileSystemItemChange(doc3.getId(), "documentCreated"));
            expectedChanges.add(new SimpleFileSystemItemChange(doc2.getId(), "deleted"));
            expectedChanges.add(new SimpleFileSystemItemChange(locallyEditedCollection.getId(), "documentModified"));
            expectedChanges.add(new SimpleFileSystemItemChange(doc1.getId(), "deleted"));

            log.trace("Unregister the 'Locally Edited' collection as a sync root");
            nuxeoDriveManager.unregisterSynchronizationRoot(session.getPrincipal(), locallyEditedCollection, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Expecting 1 change: deleted for 'Locally Edited' collection
            changes = getChanges(session.getPrincipal());
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("deleted", change.getEventId());
            assertEquals(locallyEditedCollection.getId(), change.getDocUuid());

            log.trace("Register the 'Locally Edited' collection back as a sync root");
            nuxeoDriveManager.registerSynchronizationRoot(session.getPrincipal(), locallyEditedCollection, session);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Expecting 1 change: rootRegistered for 'Locally Edited'
            // collection
            changes = getChanges(session.getPrincipal());
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("rootRegistered", change.getEventId());
            assertEquals(locallyEditedCollection.getId(), change.getDocUuid());

            log.trace("Delete the 'Locally Edited' collection");
            locallyEditedCollection.followTransition(LifeCycleConstants.DELETE_TRANSITION);
        } finally {
            commitAndWaitForAsyncCompletion();
        }

        try {
            // Expecting 1 change: deleted for 'Locally Edited' collection
            changes = getChanges(session.getPrincipal());
            assertEquals(1, changes.size());
            change = changes.get(0);
            assertEquals("deleted", change.getEventId());
            assertEquals(locallyEditedCollection.getId(), change.getDocUuid());
        } finally {
            commitAndWaitForAsyncCompletion();
        }
    }

    /**
     * Gets the document changes for the given user's synchronization roots using the {@link AuditChangeFinder} and
     * updates {@link #lastEventLogId}.
     *
     * @throws ClientException
     */
    protected List<FileSystemItemChange> getChanges(Principal principal) throws InterruptedException, ClientException {
        return getChangeSummary(principal).getFileSystemChanges();
    }

    /**
     * Gets the document changes for the Administrator user.
     */
    protected List<FileSystemItemChange> getChanges() throws InterruptedException, ClientException {
        return getChanges(session.getPrincipal());
    }

    /**
     * Gets the document changes summary for the given user's synchronization roots using the {@link NuxeoDriveManager}
     * and updates {@link #lastEventLogId}.
     */
    protected FileSystemChangeSummary getChangeSummary(Principal principal) throws ClientException,
            InterruptedException {
        Map<String, Set<IdRef>> lastSyncActiveRootRefs = RootDefinitionsHelper.parseRootDefinitions(lastSyncActiveRootDefinitions);
        FileSystemChangeSummary changeSummary = nuxeoDriveManager.getChangeSummaryIntegerBounds(principal,
                lastSyncActiveRootRefs, lastEventLogId);
        assertNotNull(changeSummary);
        lastEventLogId = changeSummary.getUpperBound();
        lastSyncActiveRootDefinitions = changeSummary.getActiveSynchronizationRootDefinitions();
        return changeSummary;
    }

    protected void commitAndWaitForAsyncCompletion(CoreSession session) throws Exception {
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
        eventService.waitForAsyncCompletion();
    }

    protected void commitAndWaitForAsyncCompletion() throws Exception {
        commitAndWaitForAsyncCompletion(session);
    }

    protected void setPermissions(DocumentModel doc, ACE... aces) throws Exception {
        ACP acp = session.getACP(doc.getRef());
        ACL localACL = acp.getOrCreateACL(ACL.LOCAL_ACL);
        for (int i = 0; i < aces.length; i++) {
            localACL.add(i, aces[i]);
        }
        session.setACP(doc.getRef(), acp, true);
        commitAndWaitForAsyncCompletion();
    }

    protected void resetPermissions(DocumentModel doc, String userName) throws Exception {
        ACP acp = session.getACP(doc.getRef());
        ACL localACL = acp.getOrCreateACL(ACL.LOCAL_ACL);
        Iterator<ACE> localACLIt = localACL.iterator();
        while (localACLIt.hasNext()) {
            ACE ace = localACLIt.next();
            if (userName.equals(ace.getUsername())) {
                localACLIt.remove();
            }
        }
        session.setACP(doc.getRef(), acp, true);
        commitAndWaitForAsyncCompletion();
    }

    protected void cleanUpAuditLog() {

        NXAuditEventsService auditService = (NXAuditEventsService) Framework.getRuntime().getComponent(
                NXAuditEventsService.NAME);
        ((DefaultAuditBackend) auditService.getBackend()).getOrCreatePersistenceProvider().run(true, new RunVoid() {
            @Override
            public void runWith(EntityManager em) throws ClientException {
                em.createNativeQuery("delete from nxp_logs_mapextinfos").executeUpdate();
                em.createNativeQuery("delete from nxp_logs_extinfo").executeUpdate();
                em.createNativeQuery("delete from nxp_logs").executeUpdate();
            }
        });
    }

    protected List<SimpleFileSystemItemChange> toSimpleFileSystemItemChanges(List<FileSystemItemChange> changes) {
        List<SimpleFileSystemItemChange> simpleChanges = new ArrayList<>();
        for (FileSystemItemChange change : changes) {
            simpleChanges.add(new SimpleFileSystemItemChange(change.getDocUuid(), change.getEventId()));
        }
        return simpleChanges;
    }

    protected void checkAuditChanges(List<SimpleFileSystemItemChange> expectedChanges,
            List<SimpleFileSystemItemChange> changes) {
        Iterator<SimpleFileSystemItemChange> it = expectedChanges.iterator();
        while (it.hasNext()) {
            SimpleFileSystemItemChange expectedChange = it.next();
            if (!changes.contains(expectedChange)) {
                fail(String.format("Change summary should contain %s", expectedChange));
            }
            changes.remove(expectedChange);
        }
        if (!changes.isEmpty()) {
            fail("Change summary contains too many elements");
        }
    }

    protected final class SimpleFileSystemItemChange {

        protected String docId;

        protected String eventName;

        public SimpleFileSystemItemChange(String docId, String eventName) {
            this.docId = docId;
            this.eventName = eventName;
        }

        public String getDocId() {
            return docId;
        }

        public String getEventName() {
            return eventName;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof SimpleFileSystemItemChange)) {
                return false;
            }
            return StringUtils.equals(((SimpleFileSystemItemChange) obj).getDocId(), docId)
                    && StringUtils.equals(((SimpleFileSystemItemChange) obj).getEventName(), eventName);
        }

        @Override
        public String toString() {
            return "(" + docId + ", " + eventName + ")";
        }
    }

}