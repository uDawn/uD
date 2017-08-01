import org.apache.commons.codec.binary.Hex;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.TransactionEventException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static junit.framework.Assert.*;

public class Operation {
    private Collection<SampleOrg> testSampleOrgs;
    private static final TestConfig testConfig = TestConfig.getConfig();
    private static final String CHANNEL_NAME = "mychannel";

    private static final String ADMIN_NAME = "admin";
    private static final String USER_1_NAME = "user1";
    private static final String FIXTURES_PATH = "src/channel/";

    private static final String CHAIN_CODE_NAME = "mycc_yl";
    private static final String CHAIN_CODE_PATH = "github.com/example_cc";
    private static final String CHAIN_CODE_VERSION = "1";

    private String testTxID = null;

    private HFClient client;
    private SampleOrg sampleOrg;
    private Channel myChannel;
    private final ChaincodeID chaincodeID;

    public Operation() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException, MalformedURLException {
        testSampleOrgs = testConfig.getIntegrationTestsSampleOrgs();
        this.sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        this.chaincodeID = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                .setVersion(CHAIN_CODE_VERSION)
                .setPath(CHAIN_CODE_PATH).build();
       // this.myChannel.setTransactionWaitTime(testConfig.getTransactionWaitTime());
       // this.myChannel.setDeployWaitTime(testConfig.getDeployWaitTime());
        //Set up hfca for each sample org

        for (SampleOrg sampleOrg : testSampleOrgs) {
            sampleOrg.setCAClient(HFCAClient.createNewInstance(sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
        }

        try {
            this.client = HFClient.createNewInstance();
            this.client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
            //Set up USERS

            //Persistence is not part of SDK. Sample file store is for demonstration purposes only!
            //   MUST be replaced with more robust application implementation  (Database, LDAP)
            File sampleStoreFile = new File(System.getProperty("java.io.tmpdir") + "/HFCSampletest.properties");
            if (sampleStoreFile.exists()) { //For testing start fresh
                sampleStoreFile.delete();
            }

            final SampleStore sampleStore = new SampleStore(sampleStoreFile);
            //  sampleStoreFile.deleteOnExit();

            //SampleUser can be any implementation that implements org.hyperledger.fabric.sdk.User Interface

            ////////////////////////////
            // get users for all orgs

            for (SampleOrg sampleOrg : testSampleOrgs) {

                HFCAClient ca = sampleOrg.getCAClient();
                final String orgName = sampleOrg.getName();
                final String mspid = sampleOrg.getMSPID();
                ca.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
                SampleUser admin = sampleStore.getMember(ADMIN_NAME, orgName);
                if (!admin.isEnrolled()) {  //Preregistered admin only needs to be enrolled with Fabric caClient.
                    admin.setEnrollment(ca.enroll(admin.getName(), "adminpw"));
                    admin.setMspId(mspid);
                }

                sampleOrg.setAdmin(admin); // The admin of this org --

                SampleUser user = sampleStore.getMember(USER_1_NAME, sampleOrg.getName());
                /*if (!user.isRegistered()) {  // users need to be registered AND enrolled
                    RegistrationRequest rr = new RegistrationRequest(user.getName(), "org1.department1");
                    user.setEnrollmentSecret(ca.register(rr, admin));
                }
                if (!user.isEnrolled()) {
                    user.setEnrollment(ca.enroll(user.getName(), user.getEnrollmentSecret()));
                    user.setMspId(mspid);
                }*/
                sampleOrg.addUser(user); //Remember user belongs to this Org

                final String sampleOrgName = sampleOrg.getName();
                final String sampleOrgDomainName = sampleOrg.getDomainName();

                // src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/

                SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                        Util.findFileSk(Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/",
                                sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
                        Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/", sampleOrgDomainName,
                                format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName)).toFile());

                sampleOrg.setPeerAdmin(peerOrgAdmin); //A special user that can create channels, join peers and install chaincode

            }

            ////////////////////////////
            //Construct and run the channels
            this.myChannel = constructChannel(CHANNEL_NAME, client, sampleOrg);
            //runChannel(this.client, this.myChannel, true, this.sampleOrg, 0);
            this.myChannel.shutdown(true); // Force mychannel to shutdown clean up resources.
            out("\n");

            /*sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg2");
            Channel barChannel = constructChannel(BAR_CHANNEL_NAME, client, sampleOrg);
            runChannel(client, barChannel, true, sampleOrg, 100); //run a newly constructed bar channel with different b value!
            //let bar channel just shutdown so we have both scenarios.

            out("\nTraverse the blocks for chain %s ", barChannel.getName());
            blockWalker(barChannel);
            out("That's all folks!");*/
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    //CHECKSTYLE.ON: Method length is 320 lines (max allowed is 150).

    private Channel constructChannel(String name, HFClient client, SampleOrg sampleOrg) throws Exception {
        ////////////////////////////
        //Construct the channel
        //

        out("Constructing channel %s", name);

        //Only peer Admin org
        client.setUserContext(sampleOrg.getPeerAdmin());

        Collection<Orderer> orderers = new LinkedList<>();

        for (String orderName : sampleOrg.getOrdererNames()) {

            Properties ordererProperties = testConfig.getOrdererProperties(orderName);

            //example of setting keepAlive to avoid timeouts on inactive http2 connections.
            // Under 5 minutes would require changes to server side to accept faster ping rates.
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});

            orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName),
                    ordererProperties));
        }

        //Just pick the first orderer in the list to create the channel.

        Orderer anOrderer = orderers.iterator().next();
        orderers.remove(anOrderer);

        ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(FIXTURES_PATH +  "channel.tx"));

        //Create channel that has only one signer that is this orgs peer admin. If channel creation policy needed more signature they would need to be added too.
        Channel newChannel = client.newChannel(name, anOrderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin()));

        out("Created channel %s", name);

        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName);

            Properties peerProperties = testConfig.getPeerProperties(peerName); //test properties for peer.. if any.
            if (peerProperties == null) {
                peerProperties = new Properties();
            }
            //Example of setting specific options on grpc's NettyChannelBuilder
            peerProperties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);

            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            newChannel.joinPeer(peer);
            out("Peer %s joined channel %s", peerName, name);
            sampleOrg.addPeer(peer);
        }

        for (Orderer orderer : orderers) { //add remaining orderers if any.
            newChannel.addOrderer(orderer);
        }

        for (String eventHubName : sampleOrg.getEventHubNames()) {

            final Properties eventHubProperties = testConfig.getEventHubProperties(eventHubName);

            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});

            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    eventHubProperties);
            newChannel.addEventHub(eventHub);
        }

        newChannel.initialize();

        out("Finished initialization channel %s", name);

        return newChannel;

    }

    static void out(String format, Object... args) {

        System.err.flush();
        System.out.flush();

        System.out.println(format(format, args));
        System.err.flush();
        System.out.flush();

    }

    public boolean transfer(String account_1, String account_2, String amount) {
        String tmp_account_1 = account_1;
        String tmp_account_2 = account_2;
        String tmp_amount = amount;
        final String channelName = this.myChannel.getName();
        out("Running channel %s", channelName);
        this.myChannel.setTransactionWaitTime(testConfig.getTransactionWaitTime());                                  
        this.myChannel.setDeployWaitTime(testConfig.getDeployWaitTime());
        Collection<Peer> channelPeers = this.myChannel.getPeers();
        Collection<Orderer> orderers = this.myChannel.getOrderers();

        //Collection<ProposalResponse> responses;
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();

        //responses = this.myChannel.sendTransactionProposal().sendInstantiationProposal(instantiateProposalRequest, channel.getPeers());

        //this.myChannel.sendTransaction(successful, orderers).thenApply(transactionEvent -> {

        try {
            successful.clear();
            failed.clear();

            this.client.setUserContext(sampleOrg.getUser(USER_1_NAME));

            ///////////////
            /// Send transaction proposal to all peers
            TransactionProposalRequest transactionProposalRequest = this.client.newTransactionProposalRequest();
            transactionProposalRequest.setChaincodeID(this.chaincodeID);
            transactionProposalRequest.setFcn("invoke");
            transactionProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
            transactionProposalRequest.setArgs(new String[]{"move", tmp_account_1, tmp_account_2, tmp_amount});

            Map<String, byte[]> tm2 = new HashMap<>();
            tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
            tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8));
            tm2.put("result", ":)".getBytes(UTF_8));  /// This should be returned see chaincode.
            transactionProposalRequest.setTransientMap(tm2);

            out(String.format("sending transactionProposal to all peers with arguments: move(%s,%s,%s)", tmp_account_1, tmp_account_2, tmp_amount));

            Collection<ProposalResponse> transactionPropResp = this.myChannel.sendTransactionProposal(transactionProposalRequest, this.myChannel.getPeers());
            for (ProposalResponse response : transactionPropResp) {
                if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    out("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                    successful.add(response);
                } else {
                    failed.add(response);
                }
            }

            // Check that all the proposals are consistent with each other. We should have only one set
            // where all the proposals above are consistent.
            Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
            if (proposalConsistencySets.size() != 1) {
                fail(format("Expected only one set of consistent proposal responses but got %d", proposalConsistencySets.size()));
            }

            out("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
                    transactionPropResp.size(), successful.size(), failed.size());
            if (failed.size() > 0) {
                ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
                fail(String.format("Not enough endorsers for invoke(move %s,%s,%s):", tmp_account_1, tmp_account_2, tmp_amount) + failed.size() + " endorser error: " +
                        firstTransactionProposalResponse.getMessage() +
                        ". Was verified: " + firstTransactionProposalResponse.isVerified());
            }
            out("Successfully received transaction proposal responses.");

            ProposalResponse resp = transactionPropResp.iterator().next();
            byte[] x = resp.getChaincodeActionResponsePayload(); // This is the data returned by the chaincode.
            String resultAsString = null;
            if (x != null) {
                resultAsString = new String(x, "UTF-8");
            }
            assertEquals(":)", resultAsString);

            assertEquals(200, resp.getChaincodeActionResponseStatus()); //Chaincode's status.

            TxReadWriteSetInfo readWriteSetInfo = resp.getChaincodeActionResponseReadWriteSetInfo();
            //See blockwalker below how to transverse this
            assertNotNull(readWriteSetInfo);
            assertTrue(readWriteSetInfo.getNsRwsetCount() > 0);

            ChaincodeID cid = resp.getChaincodeID();
            assertNotNull(cid);
            //assertEquals(CHAIN_CODE_PATH, cid.getPath());
            assertEquals(CHAIN_CODE_NAME, cid.getName());
            assertEquals(CHAIN_CODE_VERSION, cid.getVersion());

            ////////////////////////////
            // Send Transaction Transaction to orderer
            out("Sending chaincode transaction(move %s,%s,%s) to orderer.", tmp_account_1, tmp_account_2, tmp_amount);
            this.myChannel.sendTransaction(successful).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            out("Caught an exception while invoking chaincode");
            e.printStackTrace();
            fail("Failed invoking chaincode with error : " + e.getMessage());
            return false;
        }

        //return null;

        //});

        //return true;
    }

    public String query(String account) {
        this.myChannel.setTransactionWaitTime(testConfig.getTransactionWaitTime());                                  
        this.myChannel.setDeployWaitTime(testConfig.getDeployWaitTime());
        final String channelName = this.myChannel.getName();
        String tmp_amount = null;

        out("Running channel %s", channelName);

        try {
            //this.myChannel.sendTransaction(successful, orderers).thenApply(transactionEvent -> {
            try {
                //testTxID = transactionEvent.getTransactionID(); // used in the channel queries later

                ////////////////////////////
                // Send Query Proposal to all peers
                //
                String expect = "" + (300);
                out("Now query chaincode for the value of b.");
                QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
                queryByChaincodeRequest.setArgs(new String[]{"query", "b"});
                queryByChaincodeRequest.setFcn("invoke");
                queryByChaincodeRequest.setChaincodeID(chaincodeID);

                Map<String, byte[]> tm2 = new HashMap<>();
                tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
                tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
                queryByChaincodeRequest.setTransientMap(tm2);

                Collection<ProposalResponse> queryProposals = this.myChannel.queryByChaincode(queryByChaincodeRequest, this.myChannel.getPeers());
                for (ProposalResponse proposalResponse : queryProposals) {
                    if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                        fail("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                                ". Messages: " + proposalResponse.getMessage()
                                + ". Was verified : " + proposalResponse.isVerified());
                    } else {
                        String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                        tmp_amount = payload;
                        out("Query payload of b from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
                        assertEquals(payload, expect);
                    }
                }

                return tmp_amount;
            } catch (Exception e) {
                out("Caught exception while running query");
                e.printStackTrace();
                fail("Failed during chaincode query with error : " + e.getMessage());
            }
            /*}).exceptionally(e -> {
                if (e instanceof TransactionEventException) {
                    BlockEvent.TransactionEvent te = ((TransactionEventException) e).getTransactionEvent();
                    if (te != null) {
                        fail(format("Transaction with txid %s failed. %s", te.getTransactionID(), e.getMessage()));
                    }
                }
                fail(format("Test failed with %s exception %s", e.getClass().getName(), e.getMessage()));

                return null;
            }).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);*/

        } catch (Exception e) {
            out("Caught an exception running channel %s", this.myChannel.getName());
            e.printStackTrace();
            fail("Test failed with error : " + e.getMessage());
        }

        return null;
    }

    public boolean initiate(String account, String amount) {
        final String channelName = this.myChannel.getName();
        boolean isFooChain = CHANNEL_NAME.equals(channelName);
        out("Running channel %s", channelName);
        this.myChannel.setTransactionWaitTime(testConfig.getTransactionWaitTime());
        this.myChannel.setDeployWaitTime(testConfig.getDeployWaitTime());

        Collection<Peer> channelPeers = this.myChannel.getPeers();
        Collection<Orderer> orderers = this.myChannel.getOrderers();

        Collection<ProposalResponse> responses;
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();

        try {
            InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
            instantiateProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
            instantiateProposalRequest.setChaincodeID(chaincodeID);
            instantiateProposalRequest.setFcn("init");
            instantiateProposalRequest.setArgs(new String[]{"a", "500", "b", "" + (amount)});
            Map<String, byte[]> tm = new HashMap<>();
            tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
            tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
            instantiateProposalRequest.setTransientMap(tm);

            /*
              policy OR(Org1MSP.member, Org2MSP.member) meaning 1 signature from someone in either Org1 or Org2
              See README.md Chaincode endorsement policies section for more details.
            */
            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
            chaincodeEndorsementPolicy.fromYamlFile(new File(FIXTURES_PATH + "chaincodeendorsementpolicy.yaml"));
            instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

            out("Sending instantiateProposalRequest to all peers with arguments: a and b set to 100 and %s respectively", "" + (amount));
            successful.clear();
            failed.clear();

            if (isFooChain) {  //Send responses both ways with specifying peers and by using those on the channel.
                responses = this.myChannel.sendInstantiationProposal(instantiateProposalRequest, this.myChannel.getPeers());
            } else {
                responses = this.myChannel.sendInstantiationProposal(instantiateProposalRequest);

            }
            for (ProposalResponse response : responses) {
                if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);
                    out("Succesful instantiate proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                } else {
                    failed.add(response);
                }
            }
            out("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());
            if (failed.size() > 0) {
                ProposalResponse first = failed.iterator().next();
                fail("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
            }

            ///////////////
            /// Send instantiate transaction to orderer
            out("Sending instantiateTransaction to orderer with a and b set to 100 and %s respectively", "" + (amount));
            this.myChannel.sendTransaction(successful, orderers).thenApply(transactionEvent -> {

                //waitOnFabric(0);

                assertTrue(transactionEvent.isValid()); // must be valid to be here.
                out("Finished instantiate transaction with transaction id %s", transactionEvent.getTransactionID());

                return true;
            });
        } catch (Exception e) {

        }

        return false;
    }
}
