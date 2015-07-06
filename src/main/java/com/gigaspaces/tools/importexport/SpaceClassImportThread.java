package com.gigaspaces.tools.importexport;

import com.gigaspaces.logger.Constants;
import com.gigaspaces.metadata.SpaceTypeDescriptorBuilder;
import com.gigaspaces.metadata.index.SpaceIndex;
import com.gigaspaces.metadata.index.SpaceIndexFactory;
import com.gigaspaces.metadata.index.SpaceIndexType;
import com.gigaspaces.tools.importexport.serial.SerialAudit;
import com.gigaspaces.tools.importexport.serial.SerialList;
import com.gigaspaces.tools.importexport.serial.SerialMap;
import org.openspaces.core.GigaSpace;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static com.gigaspaces.tools.importexport.SpaceClassExportTask.*;

public class SpaceClassImportThread extends Thread {

    private final static java.util.logging.Logger logger = java.util.logging.Logger.getLogger(Constants.LOGGER_COMMON);

    private GigaSpace space;
    private File file;
    private String className;
    private Integer batch = 1000;
    private SerialAudit lines;

    public SpaceClassImportThread(GigaSpace space, File file, Integer batch) {
        this.space = space;
        this.file = file;
        this.batch = batch;
        this.lines = new SerialAudit();
    }

    public SerialAudit getMessage() {

        return lines;
    }

    private void registerTypeDescriptor(String typeName, SerialMap typeMap) {

        SpaceTypeDescriptorBuilder typeBuilder = new SpaceTypeDescriptorBuilder(typeName);

        // is this document already registered?
        if (typeAlreadyRegistered(typeName)) {
            logger.info("found type descriptor for " + typeName);
            lines.add("found type descriptor for " + typeName);
            return;
        }
        // create it if necessary
        logger.info("creating type descriptor for " + typeName);
        lines.add("creating type descriptor for " + typeName);

        // deal with spaceId, spaceRouting, indexes separately
        handleSpaceId(typeName, typeMap, typeBuilder);
        handleRouting(typeName, typeMap, typeBuilder);

        // space id is indexed, so it will show up here, too. just log it and continue
        for (String propertyName : typeMap.keySet()) {
            if (INDEX.equals(propertyName)) {
                SerialMap indexMap = (SerialMap) typeMap.get(propertyName);
                for (String indexType : indexMap.keySet()) {
                    SpaceIndexType type = SpaceIndexType.valueOf(indexType);
                    SerialList indexes = (SerialList) indexMap.get(indexType);
                    for (String index : indexes) {
                        SpaceIndex spaceIndex = SpaceIndexFactory.createPropertyIndex(index, type);
                        try {
                            logger.fine("creating index " + spaceIndex.toString() + " for type " + typeName);
                            typeBuilder.addIndex(spaceIndex);
                        } catch (IllegalArgumentException iae) {
                            logger.warning("registerTypeDescriptor" + iae.getMessage());
                        }
                    }
                }
            }
        }
        // Register type:
        space.getTypeManager().registerTypeDescriptor(typeBuilder.create());
    }

    @Override
    public void run() {
        ObjectInputStream input = null;
        try {
            GZIPInputStream zis = new GZIPInputStream(new BufferedInputStream(new FileInputStream(file)));
            input = new ObjectInputStream(zis);
            logger.info("opened import file " + file.toString());
            lines.add("opened import file " + file.toString());
            className = input.readUTF();
            Integer objectCount = input.readInt();
            String typeName = isDocument() ? input.readUTF() : className;
            SerialMap propertyMap = (SerialMap) input.readObject();
            registerTypeDescriptor(typeName, propertyMap);
            // we log classname, so set it up to reflect the space document type
            className = typeName + " (" + className + ")";

            logger.info("found " + objectCount + " instances of " + className);
            lines.add("found " + objectCount + " instances of " + className);

            List objectList = new ArrayList();
            Long start = System.currentTimeMillis();
            for (int i = 0; i < objectCount; i++) {
                objectList.add(input.readObject());
                if (i > 0 && (i % batch == 0 || i + 1 == objectCount)) {
                    space.writeMultiple(objectList.toArray(new Object[batch]));
                    if (i + 1 < objectCount) {
                        objectList.clear();
                    }
                }
            }
            Long duration = (System.currentTimeMillis() - start);
            logger.info("import operation took " + duration + " millis");
            lines.add("import operation took " + duration + " millis");

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }   catch (ClassNotFoundException e) {
            e.printStackTrace();
        }   finally {
            if (input != null){
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean isDocument() {
        return className.equals(DOCUMENT);
    }

    private void handleRouting(String typeName, SerialMap typeMap, SpaceTypeDescriptorBuilder typeBuilder) {
        if (typeMap.keySet().contains(ROUTING)) {
            logger.fine("creating routing property " + typeMap.get(ROUTING) + " for type " + typeName);
            lines.add("creating routing property " + typeMap.get(ROUTING) + " for type " + typeName);
            typeBuilder.routingProperty((String) typeMap.get(ROUTING));
        }
    }

    private void handleSpaceId(String typeName, SerialMap typeMap, SpaceTypeDescriptorBuilder typeBuilder) {
        if (typeMap.keySet().contains(SPACEID)) {
            logger.fine("creating id property " + typeMap.get(SPACEID) + " for type " + typeName);
            lines.add("creating id property " + typeMap.get(SPACEID) + " for type " + typeName);
            typeBuilder.idProperty((String) typeMap.get(SPACEID));
        }
    }

    private boolean typeAlreadyRegistered(String typeName) {
        return space.getTypeManager().getTypeDescriptor(typeName) != null;
    }
}