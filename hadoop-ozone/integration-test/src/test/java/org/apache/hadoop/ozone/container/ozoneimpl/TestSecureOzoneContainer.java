/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.container.ozoneimpl;

import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.MockDatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandResponseProto;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.pipeline.MockPipeline;
import org.apache.hadoop.hdds.security.exception.SCMSecurityException;
import org.apache.hadoop.hdds.security.token.ContainerTokenIdentifier;
import org.apache.hadoop.hdds.security.token.ContainerTokenSecretManager;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClientTestImpl;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.XceiverClientGrpc;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeStateMachine;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.ozone.test.GenericTestUtils;
import org.apache.ratis.util.ExitUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.apache.hadoop.hdds.HddsConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.HDDS_DATANODE_DIR_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.DFS_CONTAINER_IPC_PORT_DEFAULT;
import static org.apache.hadoop.ozone.container.ContainerTestHelper.getCreateContainerSecureRequest;
import static org.apache.hadoop.ozone.container.ContainerTestHelper.getTestContainerID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests ozone containers via secure grpc/netty.
 */
@RunWith(Parameterized.class)
public class TestSecureOzoneContainer {
  private static final Logger LOG = LoggerFactory.getLogger(
      TestSecureOzoneContainer.class);
  /**
   * Set the timeout for every test.
   */
  @Rule
  public Timeout testTimeout = Timeout.seconds(300);

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private OzoneConfiguration conf;
  private SecurityConfig secConfig;
  private final boolean requireToken;
  private final boolean hasToken;
  private final boolean tokenExpired;
  private CertificateClientTestImpl caClient;
  private ContainerTokenSecretManager secretManager;

  public TestSecureOzoneContainer(Boolean requireToken,
      Boolean hasToken, Boolean tokenExpired) {
    this.requireToken = requireToken;
    this.hasToken = hasToken;
    this.tokenExpired = tokenExpired;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> blockTokenOptions() {
    return Arrays.asList(new Object[][] {
        {true, true, false},
        {true, true, true},
        {true, false, false},
        {false, true, false},
        {false, false, false}});
  }

  @Before
  public void setup() throws Exception {
    DefaultMetricsSystem.setMiniClusterMode(true);
    ExitUtils.disableSystemExit();
    conf = new OzoneConfiguration();
    String ozoneMetaPath =
        GenericTestUtils.getTempPath("ozoneMeta");
    conf.set(OZONE_METADATA_DIRS, ozoneMetaPath);
    secConfig = new SecurityConfig(conf);
    caClient = new CertificateClientTestImpl(conf);
    secretManager = new ContainerTokenSecretManager(
        new SecurityConfig(conf), TimeUnit.DAYS.toMillis(1));
  }

  @Test
  public void testCreateOzoneContainer() throws Exception {
    LOG.info("Test case: requireBlockToken: {} hasBlockToken: {} " +
        "blockTokenExpired: {}.", requireToken, hasToken,
        tokenExpired);
    conf.setBoolean(HddsConfigKeys.HDDS_BLOCK_TOKEN_ENABLED, requireToken);
    conf.setBoolean(HddsConfigKeys.HDDS_CONTAINER_TOKEN_ENABLED, requireToken);

    ContainerID containerID = ContainerID.valueOf(getTestContainerID());
    OzoneContainer container = null;
    System.out.println(System.getProperties().getProperty("java.library.path"));
    try {
      Pipeline pipeline = MockPipeline.createSingleNodePipeline();
      conf.set(HDDS_DATANODE_DIR_KEY, tempFolder.getRoot().getPath());
      conf.setInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT, pipeline
          .getFirstNode().getPort(DatanodeDetails.Port.Name.STANDALONE)
          .getValue());
      conf.setBoolean(OzoneConfigKeys.DFS_CONTAINER_IPC_RANDOM_PORT, false);

      DatanodeDetails dn = MockDatanodeDetails.randomDatanodeDetails();
      container = new OzoneContainer(dn, conf, getContext(dn), caClient);
      //Set scmId and manually start ozone container.
      container.start(UUID.randomUUID().toString());

      String user = "user1";
      UserGroupInformation ugi = UserGroupInformation.createUserForTesting(
          user,  new String[] {"usergroup"});

      int port = dn.getPort(DatanodeDetails.Port.Name.STANDALONE).getValue();
      if (port == 0) {
        port = secConfig.getConfiguration().getInt(OzoneConfigKeys
                .DFS_CONTAINER_IPC_PORT, DFS_CONTAINER_IPC_PORT_DEFAULT);
      }
      secretManager.start(caClient);

      ugi.doAs((PrivilegedAction<Void>) () -> {
        try {
          XceiverClientGrpc client = new XceiverClientGrpc(pipeline, conf);
          client.connect();

          Token<?> token = null;
          if (hasToken) {
            Instant expiryDate = tokenExpired
                ? Instant.now().minusSeconds(3600)
                : Instant.now().plusSeconds(3600);
            ContainerTokenIdentifier tokenIdentifier =
                new ContainerTokenIdentifier(user, containerID,
                    caClient.getCertificate().getSerialNumber().toString(),
                    expiryDate);
            token = secretManager.generateToken(tokenIdentifier);
          }

          ContainerCommandRequestProto request =
              getCreateContainerSecureRequest(containerID.getId(),
                  client.getPipeline(), token);
          ContainerCommandResponseProto response = client.sendCommand(request);
          assertNotNull(response);
          ContainerProtos.Result expectedResult =
              !requireToken || (hasToken && !tokenExpired)
                  ? ContainerProtos.Result.SUCCESS
                  : ContainerProtos.Result.BLOCK_TOKEN_VERIFICATION_FAILED;
          assertEquals(expectedResult, response.getResult(), this::testCase);
        } catch (SCMSecurityException e) {
          assertState(requireToken && hasToken && tokenExpired);
        } catch (IOException e) {
          assertState(requireToken && !hasToken);
        } catch (Exception e) {
          fail(e);
        }
        return null;
      });
    } finally {
      if (container != null) {
        container.stop();
      }
    }
  }

  private StateContext getContext(DatanodeDetails datanodeDetails) {
    DatanodeStateMachine stateMachine = Mockito.mock(
        DatanodeStateMachine.class);
    StateContext context = Mockito.mock(StateContext.class);
    Mockito.when(stateMachine.getDatanodeDetails()).thenReturn(datanodeDetails);
    Mockito.when(context.getParent()).thenReturn(stateMachine);
    return context;
  }

  private void assertState(boolean condition) {
    assertTrue(condition, this::testCase);
  }

  private String testCase() {
    if (!requireToken) {
      return "unsecure";
    }
    if (!hasToken) {
      return "unauthorized";
    }
    if (tokenExpired) {
      return "token expired";
    }
    return "valid token";
  }
}
