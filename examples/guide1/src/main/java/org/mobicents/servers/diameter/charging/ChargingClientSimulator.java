package org.mobicents.servers.diameter.charging;

import org.jdiameter.api.*;
import org.jdiameter.api.app.AppAnswerEvent;
import org.jdiameter.api.app.AppRequestEvent;
import org.jdiameter.api.app.AppSession;
import org.jdiameter.api.auth.events.ReAuthAnswer;
import org.jdiameter.api.auth.events.ReAuthRequest;
import org.jdiameter.api.cca.ClientCCASession;
import org.jdiameter.api.cca.events.JCreditControlAnswer;
import org.jdiameter.api.cca.events.JCreditControlRequest;
import org.jdiameter.client.api.ISessionFactory;
import org.jdiameter.client.impl.parser.MessageParser;
import org.jdiameter.common.impl.app.cca.CCASessionFactoryImpl;
import org.jdiameter.common.impl.app.cca.JCreditControlRequestImpl;
import org.mobicents.diameter.dictionary.AvpDictionary;
import org.mobicents.servers.diameter.utils.DiameterUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;

public class ChargingClientSimulator extends CCASessionFactoryImpl implements NetworkRequestListener {

    private static final Logger logger = LoggerFactory.getLogger(ChargingClientSimulator.class);

    private static final ApplicationId CCA_APP_ID = ApplicationId.createByAuthAppId(0, 4);
    private static final String CONFIG_FILE = "config-client.xml";
    private static final String DICTIONARY_FILE = "dictionary.xml";
    private static final String REALM_NAME = "mobicents.org";
    private static final String SERVER_HOST = "127.0.0.1";
    private static final String SERVER_URI = "aaa://" + SERVER_HOST + ":3868";
    private static final String USER_NAME = "alice";
    private static final String SERVICE_CONTEXT_ID = "voice@3gpp.org";
    private static final long INITIAL_UNITS = 100;
    private static final long UPDATE_UNITS = 50;

    private Stack networkStack;
    private ISessionFactory sessionFactory;
    private ClientCCASession clientSession;
    private Session session;
    private long requestNumber = 0;

    public static void main(String[] args) throws Exception {
        ChargingClientSimulator client = new ChargingClientSimulator();
        client.start();
    }

    public ChargingClientSimulator() throws Exception {
        super();
        AvpDictionary.INSTANCE.initialize(this.getClass().getClassLoader().getResourceAsStream(DICTIONARY_FILE));
        logger.info("AVP Dictionary parsed.");

        // Initialize stack manually
        networkStack = new StackImpl();
        InputStream configStream = this.getClass().getClassLoader().getResourceAsStream(CONFIG_FILE);
        Configuration config = new XMLConfiguration(configStream);
        configStream.close();
        sessionFactory = (ISessionFactory) networkStack.init(config);
        logger.info("Stack initialized.");

        // Register network listener
        Network network = networkStack.unwrap(Network.class);
        network.addNetworkRequestListener(this, CCA_APP_ID);

        // Register CCA session factory
        init(sessionFactory);
        sessionFactory.registerAppFacory(ClientCCASession.class, this);
    }

    public void start() throws Exception {
        networkStack.start();
        logger.info("Stack started.");
        Thread.sleep(5000); // Wait for server
        session = sessionFactory.getNewSession();
        clientSession = sessionFactory.getNewAppSession(session.getSessionId(), CCA_APP_ID, ClientCCASession.class, new Object[]{});
        sendCreditControlRequest(1, INITIAL_UNITS);
    }

    private void sendCreditControlRequest(int requestType, long requestedUnits) throws Exception {
        logger.info("Sending CCR with CC-Request-Type: " + requestType + ", Requested Units: " + requestedUnits);
        Request request = session.createRequest(272, CCA_APP_ID, REALM_NAME);
        request.setDestinationHost(SERVER_HOST);
        request.setDestinationRealm(REALM_NAME);

        AvpSet avps = request.getAvps();
        avps.addAvp(Avp.CC_REQUEST_TYPE, requestType, true, false);
        avps.addAvp(Avp.CC_REQUEST_NUMBER, requestNumber++, true, false);
        AvpSet subId = avps.addGroupedAvp(Avp.SUBSCRIPTION_ID, true, false);
        subId.addAvp(Avp.SUBSCRIPTION_ID_TYPE, 0, true, false); // END_USER_E164
        subId.addAvp(Avp.SUBSCRIPTION_ID_DATA, USER_NAME, true, false, true);
        avps.addAvp(Avp.SERVICE_CONTEXT_ID, SERVICE_CONTEXT_ID, true, false, true);
        if (requestType == 1 || requestType == 2) {
            AvpSet rsu = avps.addGroupedAvp(Avp.REQUESTED_SERVICE_UNIT, true, false);
            rsu.addAvp(Avp.CC_TIME, requestedUnits, true, false);
        }

        JCreditControlRequest ccr = new JCreditControlRequestImpl(request);
        clientSession.sendCreditControlRequest(ccr);
        DiameterUtilities.printMessage(request);
    }

    @Override
    public Answer processRequest(Request request) {
        logger.info("Received request: " + request);
        return null; // Client doesn't process requests
    }

    @Override
    public void doCreditControlAnswer(ClientCCASession session, JCreditControlRequest request, JCreditControlAnswer answer) throws InternalException {
        logger.info("Received CCA: " + answer);
        try {
            AvpSet avps = answer.getMessage().getAvps();
            long resultCode = avps.getAvp(Avp.RESULT_CODE).getUnsigned32();
            logger.info("Received CCA with Result-Code: " + resultCode);
            if (resultCode == 4012) {
                logger.warn("Credit limit reached!");
                sendCreditControlRequest(3, 0);
                return;
            }
            if (resultCode == 5030) {
                logger.warn("User unknown!");
                session.release();
                return;
            }
            if (resultCode == 2001) {
                AvpSet gsu = avps.getAvp(Avp.GRANTED_SERVICE_UNIT) != null ?
                        avps.getAvp(Avp.GRANTED_SERVICE_UNIT).getGrouped() : null;
                if (gsu != null) {
                    Avp ccTime = gsu.getAvp(Avp.CC_TIME);
                    if (ccTime != null) {
                        long grantedUnits = ccTime.getUnsigned32();
                        logger.info("Granted units: " + grantedUnits);
                    }
                }
                long requestType = avps.getAvp(Avp.CC_REQUEST_TYPE).getUnsigned32();
                if (requestType == 1) {
                    Thread.sleep(2000);
                    sendCreditControlRequest(2, UPDATE_UNITS);
                } else if (requestType == 2) {
                    Thread.sleep(2000);
                    sendCreditControlRequest(3, 0);
                } else if (requestType == 3) {
                    session.release();
                    networkStack.stop();
                }
            }
        } catch (Exception e) {
            logger.error("Error processing CCA", e);
        }
    }

    private String readFile(InputStream is) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(is);
        byte[] buffer = new byte[512];
        int len;
        StringBuilder builder = new StringBuilder();
        while ((len = buffered.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, len));
        }
        return builder.toString();
    }

    // Unimplemented methods
    @Override
    public void doCreditControlRequest(ServerCCASession session, JCreditControlRequest request) {}
    @Override
    public void doReAuthRequest(ClientCCASession session, ReAuthRequest request) {}
    @Override
    public void doReAuthAnswer(ServerCCASession session, ReAuthRequest request, ReAuthAnswer answer) {}
    @Override
    public void doOtherEvent(AppSession session, AppRequestEvent request, AppAnswerEvent answer) {}
}