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
package net.oneandone.lavender.index;

import net.oneandone.lavender.config.Docroot;
import net.oneandone.lavender.config.Host;
import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.World;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Receives extracted files and uploads them */
public class Distributor {
    public static Distributor open(World world, List<Host> hosts, Docroot docroot, String indexName) throws IOException {
        Node root;
        Node destroot;
        Node index;
        Map<Node, Node> targets;
        Index prev;
        Index tmp;

        targets = new LinkedHashMap<>(); // to preserve order
        prev = null;
        for (Host host : hosts) {
            root = host.open(world);
            destroot = docroot.node(root);
            index = docroot.index(root, indexName);
            if (index.exists()) {
                tmp = Index.load(index);
            } else {
                tmp = new Index();
            }
            if (prev == null) {
                prev = tmp;
            } else {
                if (!prev.equals(tmp)) {
                    throw new IOException("index mismatch");
                }
            }
            targets.put(index, destroot);
        }
        if (prev == null) {
            // no hosts!
            prev = new Index();
        }
        return new Distributor(targets, prev);
    }

    /** left: index location; right: docroot */
    private final Map<Node, Node> targets;
    private final Index prev;
    private final Index next;

    public Distributor(Map<Node, Node> targets, Index prev) {
        this.targets = targets;
        this.prev = prev;
        this.next = new Index();
    }

    public boolean write(Label label, byte[] data) throws IOException {
        Node dest;
        String destPath;
        Label prevLabel;
        boolean changed;

        prevLabel = prev.lookup(label.getOriginalPath());
        if (prevLabel == null || !Arrays.equals(prevLabel.md5(), label.md5())) {
            destPath = label.getLavendelizedPath();
            for (Node destroot : targets.values()) {
                dest = destroot.join(destPath);
                // always create parent, because even if the label exists: a changed md5 sum causes a changed path
                dest.getParent().mkdirsOpt();
                dest.writeBytes(data);
            }
            changed = true;
        } else {
            changed = false;
        }
        next.add(label);
        return changed;
    }

    /** return next index */
    public Index close() throws IOException {
        Node index;

        if (next.size() == 0) {
            return next;
        }
        for (Map.Entry<Node, Node> entry : targets.entrySet()) {
            index = entry.getKey();
            index.getParent().mkdirsOpt();
            try (OutputStream out = index.createOutputStream()) {
                next.save(out);
            }
        }
        return next;
    }
}
