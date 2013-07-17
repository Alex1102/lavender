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
package net.oneandone.lavender.publisher.svn;

import net.oneandone.lavender.publisher.Extractor;
import net.oneandone.lavender.publisher.Log;
import net.oneandone.lavender.publisher.config.Filter;
import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import net.oneandone.sushi.util.Strings;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class SvnExtractorConfig {
    private static final String SVN_PREFIX = "svn.";

    public final String name;
    public final Filter filter;
    public String svn;
    public String storage = Extractor.DEFAULT_STORAGE;
    public boolean lavendelize = true;
    public String pathPrefix = "";

    public SvnExtractorConfig(String name, Filter filter) {
        this.name = name;
        this.filter = filter;
    }

    public static Collection<SvnExtractorConfig> parse(Properties properties) {
        String key;
        String value;
        String name;
        SvnExtractorConfig config;
        Map<String, SvnExtractorConfig> result;

        result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            key = (String) entry.getKey();
            if (key.startsWith(SVN_PREFIX)) {
                key = key.substring(SVN_PREFIX.length());
                value = (String) entry.getValue();
                int idx = key.indexOf('.');
                if (idx == -1) {
                    name = key;
                    key = null;
                } else {
                    name = key.substring(0, idx);
                    key = key.substring(idx + 1);
                }
                config = result.get(name);
                if (config == null) {
                    config = new SvnExtractorConfig(name, Filter.forProperties(properties, SVN_PREFIX + name, null));
                    result.put(name, config);
                }
                if (key == null) {
                    config.svn = Strings.removeLeftOpt((String) entry.getValue(), "scm:svn:");
                } else {
                    if (key.equals("pathPrefix")) {
                        config.pathPrefix = value;
                    } else if (key.equals("storage")) {
                        config.storage = value;
                    } else if (key.equals("lavendelize")) {
                        if ("true".equals(value)) {
                            config.lavendelize = true;
                        } else if ("false".equals(value)) {
                            config.lavendelize = false;
                        } else {
                            throw new IllegalArgumentException("illegal value: " + value);
                        }
                    }
                }
            }
        }
        return result.values();
    }

    public SvnExtractor create(World world, Log log, String svnUsername, String svnPassword) throws IOException {
        FileNode lavender;
        String svnpath;
        FileNode dest;
        List<Node> resources;

        if (svn == null) {
            throw new IllegalArgumentException("missing svn url");
        }
        if (name.startsWith("/") || name.endsWith("/")) {
            throw new IllegalArgumentException();
        }
        lavender = (FileNode) world.getHome().join(".cache/lavender");
        lavender.mkdirsOpt();
        try {
            svnpath = simplify(new URI(svn).getPath());
            svnpath = svnpath.replace('/', '.');
            svnpath = Strings.removeLeftOpt(svnpath, ".svn.");
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(svn, e);
        }
        dest = lavender.join(svnpath);
        try {
            log.info("using svn cache at " + dest);
            if (dest.exists()) {
                log.info("svn switch " + svn);
                log.info(dest.exec("svn", "switch", "--non-interactive", "--no-auth-cache",
                        "--username", svnUsername, "--password", svnUsername, svn));
            } else {
                log.info("svn checkout " + svn);
                log.info(lavender.exec("svn", "checkout", "--non-interactive", "--no-auth-cache",
                        "--username", svnUsername, "--password", svnUsername, svn, dest.getName()));
            }
        } catch (IOException e) {
            throw new IOException("error creating/updating svn checkout at " + dest + ": " + e.getMessage(), e);
        }
        dest.checkDirectory();
        resources = new ArrayList<>();
        for (Node file : dest.find("**/*")) {
            if (file.isFile()) {
                resources.add(file);
            }
        }
        return new SvnExtractor(filter, storage, lavendelize, pathPrefix, resources, name, dest);
    }

    private static final String TAGS = "/tags/";

    public static String simplify(String path) {
        int idx;
        int end;

        path = path.replace("/trunk/", "/");
        idx = path.indexOf(TAGS);
        if (idx != -1) {
            end = path.indexOf('/', idx + TAGS.length());
            path = path.substring(0, idx) + path.substring(end);
        }
        return path;
    }
}
