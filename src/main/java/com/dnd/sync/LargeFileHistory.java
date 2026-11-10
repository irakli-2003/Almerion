package com.dnd.sync;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.util.*;

/** Rewrites only unpublished commits, behind an explicit confirmation and a permanent backup ref. */
final class LargeFileHistory {
    private LargeFileHistory() { }

    static List<String> oversized(Repository repo) throws IOException {
        Set<String> paths = new TreeSet<>();
        Map<ObjectId, Long> sizes = new HashMap<>();
        for (RevCommit commit : outgoing(repo)) {
            try (TreeWalk tree = new TreeWalk(repo)) {
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    if (tree.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) continue;
                    ObjectId id = tree.getObjectId(0);
                    Long size = sizes.get(id);
                    if (size == null) {
                        size = repo.open(id, Constants.OBJ_BLOB).getSize();
                        sizes.put(id, size);
                    }
                    if (size > ManualFiles.MAX_GIT_BYTES) paths.add(tree.getPathString());
                }
            }
        }
        return List.copyOf(paths);
    }

    private static List<RevCommit> outgoing(Repository repo) throws IOException {
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) return List.of();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.REVERSE, true);
            walk.markStart(walk.parseCommit(head));
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + CampaignRepo.REMOTE + "/")) {
                if (ref.getObjectId() != null) walk.markUninteresting(walk.parseCommit(ref.getObjectId()));
            }
            List<RevCommit> commits = new ArrayList<>();
            for (RevCommit commit : walk) {
                walk.parseBody(commit);
                commits.add(commit);
            }
            return commits;
        }
    }

    /** Keeps every parent/author/message and non-large blob, including local conflict-resolution commits. */
    static String repair(Repository repo) throws IOException {
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null || oversized(repo).isEmpty()) return null;
        if (repo.getRepositoryState() != RepositoryState.SAFE) {
            throw new IOException("Finish the pending Git operation before repairing oversized history.");
        }
        String branch = repo.getFullBranch();
        if (branch == null || !branch.startsWith(Constants.R_HEADS)) {
            throw new IOException("Open a campaign branch before repairing oversized history.");
        }
        String backup = "refs/almerion/large-file-backups/" + UUID.randomUUID();
        RefUpdate saved = repo.updateRef(backup);
        saved.setExpectedOldObjectId(ObjectId.zeroId());
        saved.setNewObjectId(head);
        requireUpdated(saved.update(), "Could not back up the original campaign history.");

        Map<ObjectId, ObjectId> rewritten = new HashMap<>();
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            for (RevCommit commit : outgoing(repo)) {
                DirCache cache = DirCache.newInCore();
                var builder = cache.builder();
                try (TreeWalk tree = new TreeWalk(repo)) {
                    tree.addTree(commit.getTree());
                    tree.setRecursive(true);
                    while (tree.next()) {
                        ObjectId id = tree.getObjectId(0);
                        if (tree.getFileMode(0).getObjectType() == Constants.OBJ_BLOB
                                && repo.open(id, Constants.OBJ_BLOB).getSize() > ManualFiles.MAX_GIT_BYTES) continue;
                        DirCacheEntry entry = new DirCacheEntry(tree.getPathString());
                        entry.setFileMode(tree.getFileMode(0));
                        entry.setObjectId(id);
                        builder.add(entry);
                    }
                }
                builder.finish();
                ObjectId treeId = cache.writeTree(inserter);
                List<ObjectId> parents = new ArrayList<>();
                for (RevCommit parent : commit.getParents()) parents.add(rewritten.getOrDefault(parent, parent));
                if (treeId.equals(commit.getTree()) && parents.equals(Arrays.asList(commit.getParents()))) {
                    rewritten.put(commit, commit);
                    continue;
                }
                CommitBuilder next = new CommitBuilder();
                next.setTreeId(treeId);
                next.setParentIds(parents);
                next.setAuthor(commit.getAuthorIdent());
                next.setCommitter(commit.getCommitterIdent());
                next.setEncoding(commit.getEncoding());
                next.setMessage(commit.getFullMessage());
                rewritten.put(commit, inserter.insert(next));
            }
            inserter.flush();
        }
        ObjectId repaired = rewritten.getOrDefault(head, head);
        // Update index + branch, never checkout/reset working files. The backup ref retains every original blob.
        DirCache index = repo.lockDirCache();
        try (RevWalk walk = new RevWalk(repo)) {
            var builder = index.builder();
            try (TreeWalk tree = new TreeWalk(repo)) {
                tree.addTree(walk.parseCommit(repaired).getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    DirCacheEntry entry = new DirCacheEntry(tree.getPathString());
                    entry.setFileMode(tree.getFileMode(0));
                    entry.setObjectId(tree.getObjectId(0));
                    builder.add(entry);
                }
            }
            builder.finish();
            index.write();
            RefUpdate update = repo.updateRef(branch);
            update.setExpectedOldObjectId(head);
            update.setNewObjectId(repaired);
            update.setForceUpdate(true);
            update.setRefLogMessage("Almerion manual-file repair; original: " + backup, false);
            requireUpdated(update.update(), "Campaign changed during repair. Original history is backed up at " + backup);
            if (!index.commit()) {
                RefUpdate rollback = repo.updateRef(branch);
                rollback.setExpectedOldObjectId(repaired);
                rollback.setNewObjectId(head);
                rollback.setForceUpdate(true);
                requireUpdated(rollback.update(), "Index could not be saved and branch rollback failed. Backup: " + backup);
                throw new IOException("Index could not be saved; original branch was restored. Backup: " + backup);
            }
        } finally {
            index.unlock();
        }
        return backup;
    }

    private static void requireUpdated(RefUpdate.Result result, String message) throws IOException {
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException(message + " (" + result + ")");
        }
    }
}
