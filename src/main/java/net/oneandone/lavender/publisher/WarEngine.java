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
package net.oneandone.lavender.publisher;

import net.oneandone.lavender.filter.Lavendelizer;
import net.oneandone.lavender.index.Index;
import net.oneandone.lavender.index.Label;
import net.oneandone.sushi.fs.file.FileNode;
import net.oneandone.sushi.io.Buffer;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Drives the war publishing process: Extracts resources from a war files to FileStorage and creates a new war file with Lavendelizer
 * configured. Main class of this module, used by Cli and the Publisher plugin.
 */
public class WarEngine {
    private final String svnUsername;
    private final String svnPassword;
    private final Log log;
    private final FileNode inputWar;
    private final FileNode outputWar;
    private final Map<String, Distributor> storages;
    private final FileNode outputWebXmlFile;
    private final Index outputIndex;
    private final FileNode outputNodesFile;
    private final String nodes;

    public WarEngine(String svnUsername, String svnPassword,
                     Log log, FileNode inputWar, FileNode outputWar, Distributor lavendelStorage, FileNode outputWebXmlFile,
                     Index outputIndex, FileNode outputNodesFile, String nodes) {
        this(svnUsername, svnPassword,
                log, inputWar, outputWar, defaultStorage(lavendelStorage), outputWebXmlFile, outputIndex, outputNodesFile, nodes);
    }

    private static Map<String, Distributor> defaultStorage(Distributor lavendelStorage) {
        Map<String, Distributor> storages;

        storages = new HashMap<>();
        storages.put(Extractor.DEFAULT_STORAGE, lavendelStorage);
        return storages;
    }

    /**
     * @param inputWar
     *            the existing original WAR file
     * @param outputWar
     *            the file where the updated WAR file is saved to
     * @param storages
     * @param outputIndex
     *            Index of the resources for this war
     * @param outputNodesFile
     *            the file where the nodes file is saved to
     * @param nodes
     *            the lavender nodes. Each URI must contain the scheme (http or https), hostname, optional port, and
     *            optional path. The collection must contain separate URIs for http and https.
     */
    public WarEngine(String svnUsername, String svnPassword,
                     Log log, FileNode inputWar, FileNode outputWar, Map<String, Distributor> storages, FileNode outputWebXmlFile,
                     Index outputIndex, FileNode outputNodesFile, String nodes) {
        this.svnUsername = svnUsername;
        this.svnPassword = svnPassword;
        this.log = log;
        this.inputWar = inputWar;
        this.outputWar = outputWar;
        this.storages = storages;
        this.outputWebXmlFile = outputWebXmlFile;
        this.outputIndex = outputIndex;
        this.outputNodesFile = outputNodesFile;
        this.nodes = nodes;
    }

    /**
     * Lavendelizes the WAR file and publishes resources.
     *
     * @throws IOException
     */
    public void run() throws IOException {
        long started;
        List<Extractor> extractors;
        Index index;
        long changed;

        started = System.currentTimeMillis();
        extractors = Extractor.fromWar(log, inputWar, svnUsername, svnPassword);
        changed = extract(extractors);
        for (Map.Entry<String, Distributor> entry : storages.entrySet()) {
            index = entry.getValue().close();
            //  TODO
            if (!entry.getKey().contains("flash") && index != null /* for tests */) {
                for (Label label : index) {
                    outputIndex.add(label);
                }
            }
        }
        outputNodesFile.writeString(nodes);
        mergeWebXmlFile();
        updateWarFile();
        log.info("done: " + changed + "/" + outputIndex.size() + " files changed (" + (System.currentTimeMillis() - started) + " ms)");
    }

    public long extract(Extractor... extractors) throws IOException {
        return extract(Arrays.asList(extractors));
    }

    public long extract(List<Extractor> extractors) throws IOException {
        Distributor storage;
        long changed;

        changed = 0;
        for (Extractor extractor : extractors) {
            storage = storages.get(extractor.getStorage());
            if (storage == null) {
                throw new IllegalStateException("storage not found: " + extractor.getStorage());
            }
            changed += extractor.run(storage);
        }
        return changed;
    }

    private void mergeWebXmlFile() throws IOException {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(inputWar.toPath().toFile()));

        ZipEntry entry = zin.getNextEntry();
        while (entry != null) {
            if ("WEB-INF/web.xml".equals(entry.getName())) {
                outputWebXmlFile.getWorld().getBuffer().copy(zin, outputWebXmlFile);
                break;
            }
            entry = zin.getNextEntry();
        }
        zin.close();

        String endTag = "</web-app>";
        String filterString = "";
        filterString += "\n<filter>\n";
        filterString += "  <filter-name>Lavendelizer</filter-name>\n";
        filterString += "  <filter-class>net.oneandone.lavender.filter.Lavendelizer</filter-class>\n";
        filterString += "</filter>\n";
        filterString += "<filter-mapping>\n";
        filterString += "  <filter-name>Lavendelizer</filter-name>\n";
        filterString += "  <url-pattern>/*</url-pattern>\n";
        filterString += "</filter-mapping>\n";

        String webXmlContent = outputWebXmlFile.readString();
        webXmlContent = webXmlContent.replace(endTag, filterString + endTag);
        outputWebXmlFile.writeString(webXmlContent);
    }

    private void updateWarFile() throws IOException {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(inputWar.toPath().toFile()));
        ZipOutputStream out = new ZipOutputStream(outputWar.createOutputStream());
        ZipEntry entry;
        Buffer buffer;
        String name;
        boolean log4j;

        log4j = false;
        entry = zin.getNextEntry();
        buffer = outputWar.getWorld().getBuffer();
        while (entry != null) {
            name = entry.getName();
            if (!"WEB-INF/web.xml".equals(name)) {
                ZipEntry outEntry = new ZipEntry(name);
                out.putNextEntry(outEntry);
                buffer.copy(zin, out);
                out.closeEntry();
            }
            if (name.matches("WEB-INF/lib/lavendelizer.*jar")) {
                throw new IOException("hard-coded lavandelizer.jar found in war file: " + entry.getName());
            }
            if (name.matches("WEB-INF/lib/log4j.*jar")) {
                log4j = true;
            }

            entry = zin.getNextEntry();
        }
        zin.close();

        if (!log4j) {
            throw new IOException("missing log4j.jar in war file");
        }

        ZipEntry indexEntry = new ZipEntry(Lavendelizer.LAVENDEL_IDX.substring(1));
        out.putNextEntry(indexEntry);
        outputIndex.save(out);
        out.closeEntry();

        ZipEntry nodesEntry = new ZipEntry(Lavendelizer.LAVENDEL_NODES.substring(1));
        out.putNextEntry(nodesEntry);
        outputNodesFile.writeTo(out);
        out.closeEntry();

        ZipEntry webXmlEntry = new ZipEntry("WEB-INF/web.xml");
        out.putNextEntry(webXmlEntry);
        outputWebXmlFile.writeTo(out);
        out.closeEntry();

        out.close();
    }

    //--

    public static Log createNullLog() {
        return new Log() {
            @Override
            public void warn(String str) {
            }

            @Override
            public void info(String str) {
            }

            @Override
            public void verbose(String str) {
            }
        };
    }
}
