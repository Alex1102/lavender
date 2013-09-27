/**
 * Copyright 1&1 Internet AG, https://github.com/1and1/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.oneandone.lavender.modules;

import net.oneandone.lavender.index.Index;
import net.oneandone.lavender.index.Label;
import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.filter.Filter;
import net.oneandone.sushi.fs.svn.SvnNode;
import org.tmatesoft.svn.core.ISVNDirEntryHandler;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.wc.SVNRevision;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Extracts resources from svn */
public class SvnModule extends Module<SVNDirEntry> {
    private final SvnNode root;
    private final Filter filter;

    // maps svn paths (relative to root) to revision numbers
    private final Index oldIndex;
    private final Index index;
    private final Node indexFile;

    private Map<String, SVNDirEntry> files;

    public SvnModule(Filter filter, String type, Index oldIndex, Index index, Node indexFile, SvnNode root,
                     boolean lavendelize, String resourcePathPrefix, String targetPathPrefix, String folder) {
        super(type, folder, lavendelize, resourcePathPrefix, targetPathPrefix);
        this.root = root;
        this.filter = filter;
        this.oldIndex = oldIndex;
        this.index = index;
        this.indexFile = indexFile;
        this.files = null;
    }

    protected Map<String, SVNDirEntry> files() {
        if (files == null) {
            files = new HashMap<>();
            try {
                root.getRoot().getClientMananger().getLogClient().doList(
                        root.getSvnurl(), null, SVNRevision.HEAD, true, SVNDepth.INFINITY, SVNDirEntry.DIRENT_ALL, new ISVNDirEntryHandler() {
                    @Override
                    public void handleDirEntry(SVNDirEntry entry) throws SVNException {
                        String path;

                        if (entry.getKind() == SVNNodeKind.FILE) {
                            path = entry.getRelativePath();
                            if (filter.matches(path)) {
                                if (entry.getSize() > Integer.MAX_VALUE) {
                                    throw new UnsupportedOperationException("file too big: " + path);
                                }
                                files.put(path, entry);
                            }
                        }
                    }
                });
            } catch (SVNException e) {
                throw new RuntimeException("TODO", e);
            }
        }
        return files;
    }

    @Override
    protected SvnResource createResource(String resourcePath, SVNDirEntry entry) {
        String svnPath;

        svnPath = entry.getRelativePath();
        return new SvnResource(this, entry.getRevision(), resourcePath,
                (int) entry.getSize(), entry.getDate().getTime(), root.join(svnPath), md5(entry));
    }

    protected byte[] md5(SVNDirEntry entry) {
        Label label;
        byte[] md5;

        label = oldIndex.lookup(entry.getRelativePath());
        if (label != null && label.getLavendelizedPath().equals(Long.toString(entry.getRevision()))) {
            md5 = label.md5();
            index.add(label);
        } else {
            md5 = null;
        }
        return md5;
    }

    public Index index() {
        return index;
    }

    public String uri() {
        return root.getURI().toString();
    }

    public void saveCaches() throws IOException {
        index.save(indexFile);
    }
}
