/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.ozone.container.keyvalue.helpers;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;

import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.utils.MetadataKeyFilters;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.helpers.BlockData;
import org.apache.hadoop.ozone.container.common.helpers.ChunkInfo;
import org.apache.hadoop.ozone.container.common.helpers.ContainerUtils;
import org.apache.hadoop.ozone.container.common.interfaces.BlockIterator;
import org.apache.hadoop.ozone.container.common.utils.ContainerInspectorUtil;
import org.apache.hadoop.ozone.container.common.interfaces.DBHandle;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;

import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.ozone.container.metadata.DatanodeStore;
import org.apache.hadoop.ozone.container.metadata.DatanodeStoreSchemaOneImpl;
import org.apache.hadoop.ozone.container.metadata.DatanodeStoreSchemaTwoImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class which defines utility methods for KeyValueContainer.
 */

public final class KeyValueContainerUtil {

  /* Never constructed. */
  private KeyValueContainerUtil() {

  }

  private static final Logger LOG = LoggerFactory.getLogger(
      KeyValueContainerUtil.class);

  /**
   *
   * @param containerMetaDataPath
   * @throws IOException
   */

  /**
   * creates metadata path, chunks path and metadata DB for the specified
   * container.
   *
   * @param containerMetaDataPath Path to the container's metadata directory.
   * @param chunksPath Path were chunks for this container should be stored.
   * @param dbFile Path to the container's .db file.
   * @param schemaVersion The schema version of the container. If this method
   * has not been updated after a schema version addition
   * and does not recognize the latest SchemaVersion, an
   * {@link IllegalArgumentException} is thrown.
   * @param conf The configuration to use for this container.
   * @throws IOException
   */
  public static void createContainerMetaData(
      File containerMetaDataPath, File chunksPath, File dbFile,
      String schemaVersion, ConfigurationSource conf) throws IOException {
    Preconditions.checkNotNull(containerMetaDataPath);
    Preconditions.checkNotNull(conf);

    if (!containerMetaDataPath.mkdirs()) {
      LOG.error("Unable to create directory for metadata storage. Path: {}",
          containerMetaDataPath);
      throw new IOException("Unable to create directory for metadata storage." +
          " Path: " + containerMetaDataPath);
    }

    if (!chunksPath.mkdirs()) {
      LOG.error("Unable to create chunks directory Container {}",
          chunksPath);
      //clean up container metadata path and metadata db
      FileUtils.deleteDirectory(containerMetaDataPath);
      FileUtils.deleteDirectory(containerMetaDataPath.getParentFile());
      throw new IOException("Unable to create directory for data storage." +
          " Path: " + chunksPath);
    }

    DatanodeStore store;
    if (schemaVersion.equals(OzoneConsts.SCHEMA_V1)) {
      store = new DatanodeStoreSchemaOneImpl(conf, dbFile.getAbsolutePath(),
          false);
    } else if (schemaVersion.equals(OzoneConsts.SCHEMA_V2)) {
      store = new DatanodeStoreSchemaTwoImpl(conf, dbFile.getAbsolutePath(),
          false);
    } else if (schemaVersion.equals(OzoneConsts.SCHEMA_V3)) {
      // We don't create per-container store for schema v3 containers,
      // they should use per-volume db store.
      return;
    } else {
      throw new IllegalArgumentException(
              "Unrecognized schema version for container: " + schemaVersion);
    }

    //add db handler into cache
    BlockUtils.addDB(store, dbFile.getAbsolutePath(), conf, schemaVersion);
  }

  /**
   * remove Container if it is empty.
   * <p>
   * There are three things we need to delete.
   * <p>
   * 1. Container file and metadata file. 2. The Level DB file 3. The path that
   * we created on the data location.
   *
   * @param containerData - Data of the container to remove.
   * @param conf - configuration of the cluster.
   * @throws IOException
   */
  public static void removeContainer(KeyValueContainerData containerData,
                                     ConfigurationSource conf)
      throws IOException {
    Preconditions.checkNotNull(containerData);
    File containerMetaDataPath = new File(containerData
        .getMetadataPath());
    File chunksPath = new File(containerData.getChunksPath());

    if (containerData.getSchemaVersion().equals(OzoneConsts.SCHEMA_V3)) {
      BlockUtils.removeContainerFromDB(containerData, conf);
    } else {
      // Close the DB connection and remove the DB handler from cache
      BlockUtils.removeDB(containerData, conf);
    }

    // Delete the Container MetaData path.
    FileUtils.deleteDirectory(containerMetaDataPath);

    //Delete the Container Chunks Path.
    FileUtils.deleteDirectory(chunksPath);

    //Delete Container directory
    FileUtils.deleteDirectory(containerMetaDataPath.getParentFile());
  }

  /**
   * Returns if there are no blocks in the container.
   * @param containerData Container to check
   * @param conf configuration
   * @return true if the directory containing blocks is empty
   * @throws IOException
   */
  public static boolean noBlocksInContainer(KeyValueContainerData
                                                containerData)
      throws IOException {
    Preconditions.checkNotNull(containerData);
    File chunksPath = new File(containerData.getChunksPath());
    Preconditions.checkArgument(chunksPath.isDirectory());

    try (DirectoryStream<Path> dir
             = Files.newDirectoryStream(chunksPath.toPath())) {
      return !dir.iterator().hasNext();
    }
  }

  /**
   * Parse KeyValueContainerData and verify checksum. Set block related
   * metadata like block commit sequence id, block count, bytes used and
   * pending delete block count and delete transaction id.
   * @param kvContainerData
   * @param config
   * @throws IOException
   */
  public static void parseKVContainerData(KeyValueContainerData kvContainerData,
      ConfigurationSource config) throws IOException {

    long containerID = kvContainerData.getContainerID();

    // Verify Checksum
    ContainerUtils.verifyChecksum(kvContainerData, config);

    if (kvContainerData.getSchemaVersion() == null) {
      // If this container has not specified a schema version, it is in the old
      // format with one default column family.
      kvContainerData.setSchemaVersion(OzoneConsts.SCHEMA_V1);
    }

    File dbFile = KeyValueContainerLocationUtil.getContainerDBFile(
        kvContainerData);
    if (!dbFile.exists()) {
      LOG.error("Container DB file is missing for ContainerID {}. " +
          "Skipping loading of this container.", containerID);
      // Don't further process this container, as it is missing db file.
      return;
    }
    kvContainerData.setDbFile(dbFile);

    if (kvContainerData.getSchemaVersion().equals(OzoneConsts.SCHEMA_V3)) {
      try (DBHandle db = BlockUtils.getDB(kvContainerData, config)) {
        populateContainerMetadata(kvContainerData, db.getStore());
      }
      return;
    }

    DBHandle cachedDB = null;
    DatanodeStore store = null;
    try {
      try {
        boolean readOnly = ContainerInspectorUtil.isReadOnly(
            ContainerProtos.ContainerType.KeyValueContainer);
        store = BlockUtils.getUncachedDatanodeStore(
            kvContainerData, config, readOnly);
      } catch (IOException e) {
        // If an exception is thrown, then it may indicate the RocksDB is
        // already open in the container cache. As this code is only executed at
        // DN startup, this should only happen in the tests.
        cachedDB = BlockUtils.getDB(kvContainerData, config);
        store = cachedDB.getStore();
        LOG.warn("Attempt to get an uncached RocksDB handle failed and an " +
            "instance was retrieved from the cache. This should only happen " +
            "in tests");
      }
      populateContainerMetadata(kvContainerData, store);
    } finally {
      if (cachedDB != null) {
        // If we get a cached instance, calling close simply decrements the
        // reference count.
        cachedDB.close();
      } else if (store != null) {
        // We only stop the store if cacheDB is null, as otherwise we would
        // close the rocksDB handle in the cache and the next reader would fail
        try {
          store.stop();
        } catch (IOException e) {
          throw e;
        } catch (Exception e) {
          throw new RuntimeException("Unexpected exception closing the " +
              "RocksDB when loading containers", e);
        }
      }
    }
  }

  private static void populateContainerMetadata(
      KeyValueContainerData kvContainerData, DatanodeStore store)
      throws IOException {
    boolean isBlockMetadataSet = false;
    Table<String, Long> metadataTable = store.getMetadataTable();

    // Set pending deleted block count.
    Long pendingDeleteBlockCount =
        metadataTable.get(kvContainerData
            .getPendingDeleteBlockCountKey());
    if (pendingDeleteBlockCount != null) {
      kvContainerData.incrPendingDeletionBlocks(
          pendingDeleteBlockCount);
    } else {
      // Set pending deleted block count.
      MetadataKeyFilters.KeyPrefixFilter filter =
          kvContainerData.getDeletingBlockKeyFilter();
      int numPendingDeletionBlocks = store.getBlockDataTable()
              .getSequentialRangeKVs(kvContainerData.startKeyEmpty(),
                  Integer.MAX_VALUE, kvContainerData.containerPrefix(),
                  filter).size();
      kvContainerData.incrPendingDeletionBlocks(numPendingDeletionBlocks);
    }

    // Set delete transaction id.
    Long delTxnId =
        metadataTable.get(kvContainerData.getLatestDeleteTxnKey());
    if (delTxnId != null) {
      kvContainerData
          .updateDeleteTransactionId(delTxnId);
    }

    // Set BlockCommitSequenceId.
    Long bcsId = metadataTable.get(
        kvContainerData.getBcsIdKey());
    if (bcsId != null) {
      kvContainerData
          .updateBlockCommitSequenceId(bcsId);
    }

    // Set bytes used.
    // commitSpace for Open Containers relies on usedBytes
    Long bytesUsed =
        metadataTable.get(kvContainerData.getBytesUsedKey());
    if (bytesUsed != null) {
      isBlockMetadataSet = true;
      kvContainerData.setBytesUsed(bytesUsed);
    }

    // Set block count.
    Long blockCount = metadataTable.get(
        kvContainerData.getBlockCountKey());
    if (blockCount != null) {
      isBlockMetadataSet = true;
      kvContainerData.setBlockCount(blockCount);
    }
    if (!isBlockMetadataSet) {
      initializeUsedBytesAndBlockCount(store, kvContainerData);
    }

    // If the container is missing a chunks directory, possibly due to the
    // bug fixed by HDDS-6235, create it here.
    File chunksDir = new File(kvContainerData.getChunksPath());
    if (!chunksDir.exists()) {
      Files.createDirectories(chunksDir.toPath());
    }
    // Run advanced container inspection/repair operations if specified on
    // startup. If this method is called but not as a part of startup,
    // The inspectors will be unloaded and this will be a no-op.
    ContainerInspectorUtil.process(kvContainerData, store);
  }

  /**
   * Initialize bytes used and block count.
   * @param kvData
   * @throws IOException
   */
  private static void initializeUsedBytesAndBlockCount(DatanodeStore store,
      KeyValueContainerData kvData) throws IOException {
    final String errorMessage = "Failed to parse block data for" +
        " Container " + kvData.getContainerID();
    long blockCount = 0;
    long usedBytes = 0;

    try (BlockIterator<BlockData> blockIter =
             store.getBlockIterator(kvData.getContainerID(),
                 kvData.getUnprefixedKeyFilter())) {

      while (blockIter.hasNext()) {
        blockCount++;
        try {
          usedBytes += getBlockLength(blockIter.nextBlock());
        } catch (IOException ex) {
          LOG.error(errorMessage);
        }
      }
    }

    // Count all deleting blocks.
    try (BlockIterator<BlockData> blockIter =
             store.getBlockIterator(kvData.getContainerID(),
                 kvData.getDeletingBlockKeyFilter())) {

      while (blockIter.hasNext()) {
        blockCount++;
        try {
          usedBytes += getBlockLength(blockIter.nextBlock());
        } catch (IOException ex) {
          LOG.error(errorMessage);
        }
      }
    }
    kvData.setBytesUsed(usedBytes);
    kvData.setBlockCount(blockCount);
  }

  public static long getBlockLength(BlockData block) throws IOException {
    long blockLen = 0;
    List<ContainerProtos.ChunkInfo> chunkInfoList = block.getChunks();

    for (ContainerProtos.ChunkInfo chunk : chunkInfoList) {
      ChunkInfo info = ChunkInfo.getFromProtoBuf(chunk);
      blockLen += info.getLen();
    }

    return blockLen;
  }

  /**
   * Returns the path where data or chunks live for a given container.
   *
   * @param kvContainerData - KeyValueContainerData
   * @return - Path to the chunks directory
   */
  public static Path getDataDirectory(KeyValueContainerData kvContainerData) {

    String chunksPath = kvContainerData.getChunksPath();
    Preconditions.checkNotNull(chunksPath);

    return Paths.get(chunksPath);
  }

  /**
   * Container metadata directory -- here is where the level DB and
   * .container file lives.
   *
   * @param kvContainerData - KeyValueContainerData
   * @return Path to the metadata directory
   */
  public static Path getMetadataDirectory(
      KeyValueContainerData kvContainerData) {

    String metadataPath = kvContainerData.getMetadataPath();
    Preconditions.checkNotNull(metadataPath);

    return Paths.get(metadataPath);

  }
}
