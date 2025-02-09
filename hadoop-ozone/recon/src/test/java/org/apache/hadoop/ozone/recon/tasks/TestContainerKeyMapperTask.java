/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.recon.tasks;

import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.getMockOzoneManagerServiceProvider;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.getOmKeyLocationInfo;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.getRandomPipeline;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.getTestReconOmMetadataManager;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.initializeNewOmMetadataManager;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.writeDataToOm;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.recon.ReconTestInjector;
import org.apache.hadoop.ozone.recon.api.types.ContainerKeyPrefix;
import org.apache.hadoop.ozone.recon.recovery.ReconOMMetadataManager;
import org.apache.hadoop.ozone.recon.spi.ReconContainerMetadataManager;
import org.apache.hadoop.ozone.recon.spi.impl.OzoneManagerServiceProviderImpl;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit test for Container Key mapper task.
 */
public class TestContainerKeyMapperTask {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ReconContainerMetadataManager reconContainerMetadataManager;
  private OMMetadataManager omMetadataManager;
  private ReconOMMetadataManager reconOMMetadataManager;
  private OzoneManagerServiceProviderImpl ozoneManagerServiceProvider;

  @Before
  public void setUp() throws Exception {
    omMetadataManager = initializeNewOmMetadataManager(
        temporaryFolder.newFolder());
    ozoneManagerServiceProvider = getMockOzoneManagerServiceProvider();
    reconOMMetadataManager = getTestReconOmMetadataManager(omMetadataManager,
        temporaryFolder.newFolder());

    ReconTestInjector reconTestInjector =
        new ReconTestInjector.Builder(temporaryFolder)
            .withReconSqlDb()
            .withReconOm(reconOMMetadataManager)
            .withOmServiceProvider(ozoneManagerServiceProvider)
            .withContainerDB()
            .build();
    reconContainerMetadataManager =
        reconTestInjector.getInstance(ReconContainerMetadataManager.class);
  }

  @Test
  public void testReprocessOMDB() throws Exception {

    Map<ContainerKeyPrefix, Integer> keyPrefixesForContainer =
        reconContainerMetadataManager.getKeyPrefixesForContainer(1);
    assertTrue(keyPrefixesForContainer.isEmpty());

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(2);
    assertTrue(keyPrefixesForContainer.isEmpty());

    Pipeline pipeline = getRandomPipeline();

    List<OmKeyLocationInfo> omKeyLocationInfoList = new ArrayList<>();
    BlockID blockID1 = new BlockID(1, 1);
    OmKeyLocationInfo omKeyLocationInfo1 = getOmKeyLocationInfo(blockID1,
        pipeline);

    BlockID blockID2 = new BlockID(2, 1);
    OmKeyLocationInfo omKeyLocationInfo2
        = getOmKeyLocationInfo(blockID2, pipeline);

    omKeyLocationInfoList.add(omKeyLocationInfo1);
    omKeyLocationInfoList.add(omKeyLocationInfo2);

    OmKeyLocationInfoGroup omKeyLocationInfoGroup = new
        OmKeyLocationInfoGroup(0, omKeyLocationInfoList);

    writeDataToOm(reconOMMetadataManager,
        "key_one",
        "bucketOne",
        "sampleVol",
        Collections.singletonList(omKeyLocationInfoGroup));

    ContainerKeyMapperTask containerKeyMapperTask =
        new ContainerKeyMapperTask(reconContainerMetadataManager);
    containerKeyMapperTask.reprocess(reconOMMetadataManager);

    keyPrefixesForContainer =
        reconContainerMetadataManager.getKeyPrefixesForContainer(1);
    assertEquals(1, keyPrefixesForContainer.size());
    String omKey = omMetadataManager.getOzoneKey("sampleVol",
        "bucketOne", "key_one");
    ContainerKeyPrefix containerKeyPrefix = new ContainerKeyPrefix(1,
        omKey, 0);
    assertEquals(1,
        keyPrefixesForContainer.get(containerKeyPrefix).intValue());

    keyPrefixesForContainer =
        reconContainerMetadataManager.getKeyPrefixesForContainer(2);
    assertEquals(1, keyPrefixesForContainer.size());
    containerKeyPrefix = new ContainerKeyPrefix(2, omKey,
        0);
    assertEquals(1,
        keyPrefixesForContainer.get(containerKeyPrefix).intValue());

    // Test if container key counts are updated
    assertEquals(1, reconContainerMetadataManager.getKeyCountForContainer(1L));
    assertEquals(1, reconContainerMetadataManager.getKeyCountForContainer(2L));
    assertEquals(0, reconContainerMetadataManager.getKeyCountForContainer(3L));

    // Test if container count is updated
    assertEquals(2, reconContainerMetadataManager.getCountForContainers());
  }

  @Test
  public void testProcessOMEvents() throws IOException {
    Map<ContainerKeyPrefix, Integer> keyPrefixesForContainer =
        reconContainerMetadataManager.getKeyPrefixesForContainer(1);
    assertTrue(keyPrefixesForContainer.isEmpty());

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(2);
    assertTrue(keyPrefixesForContainer.isEmpty());

    Pipeline pipeline = getRandomPipeline();

    List<OmKeyLocationInfo> omKeyLocationInfoList = new ArrayList<>();
    BlockID blockID1 = new BlockID(1, 1);
    OmKeyLocationInfo omKeyLocationInfo1 = getOmKeyLocationInfo(blockID1,
        pipeline);

    BlockID blockID2 = new BlockID(2, 1);
    OmKeyLocationInfo omKeyLocationInfo2
        = getOmKeyLocationInfo(blockID2, pipeline);

    omKeyLocationInfoList.add(omKeyLocationInfo1);
    omKeyLocationInfoList.add(omKeyLocationInfo2);

    OmKeyLocationInfoGroup omKeyLocationInfoGroup = new
        OmKeyLocationInfoGroup(0, omKeyLocationInfoList);

    String bucket = "bucketOne";
    String volume = "sampleVol";
    String key = "key_one";
    String omKey = omMetadataManager.getOzoneKey(volume, bucket, key);
    OmKeyInfo omKeyInfo = buildOmKeyInfo(volume, bucket, key,
        omKeyLocationInfoGroup);

    OMDBUpdateEvent keyEvent1 = new OMDBUpdateEvent.
        OMUpdateEventBuilder<String, OmKeyInfo>()
        .setKey(omKey)
        .setValue(omKeyInfo)
        .setTable(omMetadataManager.getKeyTable(getBucketLayout()).getName())
        .setAction(OMDBUpdateEvent.OMDBUpdateAction.PUT)
        .build();

    BlockID blockID3 = new BlockID(1, 2);
    OmKeyLocationInfo omKeyLocationInfo3 =
        getOmKeyLocationInfo(blockID3, pipeline);

    BlockID blockID4 = new BlockID(3, 1);
    OmKeyLocationInfo omKeyLocationInfo4
        = getOmKeyLocationInfo(blockID4, pipeline);

    omKeyLocationInfoList = new ArrayList<>();
    omKeyLocationInfoList.add(omKeyLocationInfo3);
    omKeyLocationInfoList.add(omKeyLocationInfo4);
    omKeyLocationInfoGroup = new OmKeyLocationInfoGroup(0,
        omKeyLocationInfoList);

    String key2 = "key_two";
    writeDataToOm(reconOMMetadataManager, key2, bucket, volume, Collections
        .singletonList(omKeyLocationInfoGroup));

    omKey = omMetadataManager.getOzoneKey(volume, bucket, key2);
    OMDBUpdateEvent keyEvent2 = new OMDBUpdateEvent.
        OMUpdateEventBuilder<String, OmKeyInfo>()
        .setKey(omKey)
        .setAction(OMDBUpdateEvent.OMDBUpdateAction.DELETE)
        .setTable(omMetadataManager.getKeyTable(getBucketLayout()).getName())
        .build();

    OMUpdateEventBatch omUpdateEventBatch = new OMUpdateEventBatch(new
        ArrayList<OMDBUpdateEvent>() {{
          add(keyEvent1);
          add(keyEvent2);
        }});

    ContainerKeyMapperTask containerKeyMapperTask =
        new ContainerKeyMapperTask(reconContainerMetadataManager);
    containerKeyMapperTask.reprocess(reconOMMetadataManager);

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(1);
    assertEquals(1, keyPrefixesForContainer.size());

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(2);
    assertTrue(keyPrefixesForContainer.isEmpty());

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(3);
    assertEquals(1, keyPrefixesForContainer.size());

    assertEquals(1, reconContainerMetadataManager.getKeyCountForContainer(1L));
    assertEquals(0, reconContainerMetadataManager.getKeyCountForContainer(2L));
    assertEquals(1, reconContainerMetadataManager.getKeyCountForContainer(3L));

    // Process PUT & DELETE event.
    containerKeyMapperTask.process(omUpdateEventBatch);

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(1);
    assertEquals(1, keyPrefixesForContainer.size());

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(2);
    assertEquals(1, keyPrefixesForContainer.size());

    keyPrefixesForContainer = reconContainerMetadataManager
        .getKeyPrefixesForContainer(3);
    assertTrue(keyPrefixesForContainer.isEmpty());

    assertEquals(1, reconContainerMetadataManager.getKeyCountForContainer(1L));
    assertEquals(1, reconContainerMetadataManager.getKeyCountForContainer(2L));
    assertEquals(0, reconContainerMetadataManager.getKeyCountForContainer(3L));

    // Test if container count is updated
    assertEquals(3, reconContainerMetadataManager.getCountForContainers());
  }

  private OmKeyInfo buildOmKeyInfo(String volume,
                                   String bucket,
                                   String key,
                                   OmKeyLocationInfoGroup
                                       omKeyLocationInfoGroup) {
    return new OmKeyInfo.Builder()
        .setBucketName(bucket)
        .setVolumeName(volume)
        .setKeyName(key)
        .setReplicationConfig(StandaloneReplicationConfig
            .getInstance(HddsProtos.ReplicationFactor.ONE))
        .setOmKeyLocationInfos(Collections.singletonList(
            omKeyLocationInfoGroup))
        .build();
  }

  private BucketLayout getBucketLayout() {
    return BucketLayout.DEFAULT;
  }
}
