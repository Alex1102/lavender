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
package net.oneandone.lavender.config;

import com.jcraft.jsch.JSchException;
import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import net.oneandone.sushi.fs.ssh.SshFilesystem;
import net.oneandone.sushi.fs.svn.SvnFilesystem;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class Settings {
    public static Settings load() throws IOException {
        return load(new World());
    }

    public static Settings load(World world) throws IOException {
        Settings settings;

        settings = settings(world);
        settings.initWorld();
        return settings;
    }

    private static Settings settings(World world) throws IOException {
        String path;
        Node file;
        Properties properties;
        List<Node> sshKeys;

        path = System.getenv("LAVENDER_SETTINGS");
        file = path != null ? world.file(path) : world.getHome().join(".lavender.settings");
        properties = file.readProperties();
        sshKeys = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("ssh.")) {
                sshKeys.add(world.file(properties.getProperty(key)));
            }
        }
        try {
            return new Settings(world, new URI(properties.getProperty("svn")), properties.getProperty("svn.username"), properties.getProperty("svn.password"), sshKeys);
        } catch (URISyntaxException e) {
            throw new IOException("invalid settings file " + file + ": " + e.getMessage(), e);
        }
    }

    //--

    public final World world;
    /** don't store the node, so I can create settings without accessing svn (and thus without svn credentials) */
    public final URI svn;
    public final String svnUsername;
    public final String svnPassword;
    private final List<Node> sshKeys;

    public Settings(World world, URI svn, String svnUsername, String svnPassword, List<Node> sshKeys) {
        this.world = world;
        this.svn = svn;
        this.svnUsername = svnUsername;
        this.svnPassword = svnPassword;
        this.sshKeys = sshKeys;
    }

    public void initLogs(FileNode logs) throws IOException {
        world.setTemp((FileNode) logs.mkdirsOpt());
    }

    private void initWorld() throws IOException {
        SshFilesystem ssh;

        initLogs((FileNode) world.getHome().join("logs/lavender"));

        world.getMemoryFilesystem().setMaxInMemorySize(Integer.MAX_VALUE);
        world.getFilesystem("svn", SvnFilesystem.class).setDefaultCredentials(svnUsername, svnPassword);
        ssh = world.getFilesystem("ssh", SshFilesystem.class);
        for (Node node : sshKeys) {
            try {
                ssh.addIdentity(node, null);
            } catch (JSchException | IOException e) {
                throw new IllegalStateException("cannot connect to flash server: " + e.getMessage(), e);
            }
        }
        // disable them for integration tests, because I don't have .ssh on pearl/gems
    }

    public Net loadNet() throws IOException {
        FileNode local;
        FileNode tmp;
        Net result;

        local = lastNetNode();
        tmp = local.getParent().createTempFile();
        world.node(svn).join("net.xml").copyFile(tmp);
        result = Net.load(tmp);
        tmp.move(local.deleteFileOpt());
        return result;
    }

    public Net loadLastNet() throws IOException {
        return Net.load(lastNetNode());
    }

    private FileNode lastNetNode() {
        return (FileNode) world.getHome().join(".lavender.net.xml");
    }
}
