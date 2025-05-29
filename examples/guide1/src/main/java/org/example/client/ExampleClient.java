package org.example.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.jdiameter.api.Answer;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.Configuration;
import org.jdiameter.api.EventListener;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.Message;
import org.jdiameter.api.MetaData;
import org.jdiameter.api.Network;
import org.jdiameter.api.Request;
import org.jdiameter.api.Session;
import org.jdiameter.api.SessionFactory;
import org.jdiameter.api.Stack;
import org.jdiameter.api.StackType;
import org.jdiameter.server.impl.StackImpl;
import org.jdiameter.server.impl.helpers.XMLConfiguration;
import org.mobicents.diameter.dictionary.AvpDictionary;
import org.mobicents.diameter.dictionary.AvpRepresentation;

public class ExampleClient implements EventListener<Request, Answer> {
  private static final Logger log = Logger.getLogger(ExampleClient.class);
  static {
    configLog4j();
  }

    private static void configLog4j() {
    InputStream inStreamLog4j = ExampleClient.class.getClassLoader().getResourceAsStream("log4j.properties");
    Properties propertiesLog4j = new Properties();
    try {
      propertiesLog4j.load(inStreamLog4j);
      PropertyConfigurator.configure(propertiesLog4j);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (inStreamLog4j != null) {
        try {
          inStreamLog4j.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    log.debug("log4j configured");
  }

  private static final String configFile = "org/example/client/client-jdiameter-config.xml";
  private static final String dictionaryFile = "org/example/client/dictionary.xml";
  private static final String serverHost = "127.0.0.1";
  private static final String serverPort = "3868";
  private static final String serverURI =  serverHost + ":" + serverPort;
  private static final String realmName = "exchange.example.org";
  private static final int commandCode = 272;
  private static final long applicationID = 4;
  private static final int CC_REQUEST_TYPE = 416;
  private static final int CC_REQUEST_NUMBER = 415;
  private static final int REQUESTED_SERVICE_UNIT = 437;
  private static final int GRANTED_SERVICE_UNIT = 431;
  private static final int CC_TOTAL_OCTETS = 412;

  private  String userName;
  private  long initialUnits;
  private  long updateUnits;
  private long nextRequestNumber = 1;
  private AvpDictionary dictionary = AvpDictionary.INSTANCE;
  private Stack stack;
  private SessionFactory factory;
  private Session session;

  public ExampleClient() {
    Properties props = new Properties();
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("client.properties")) {
      if (is != null) {
        props.load(is);
      }
      this.userName = props.getProperty("user.name", "testuser");
      this.initialUnits = Long.parseLong(props.getProperty("initial.units", "50"));
      this.updateUnits = Long.parseLong(props.getProperty("update.units", "30"));
    } catch (IOException e) {
      log.error("Failed to load client.properties, using defaults", e);
    }
  }

  private void initStack() {
    if (log.isInfoEnabled()) {
      log.info("Initializing Stack...");
    }
    InputStream is = null;
    try {
      dictionary.parseDictionary(this.getClass().getClassLoader().getResourceAsStream(dictionaryFile));
      log.info("AVP Dictionary successfully parsed.");
      this.stack = new StackImpl();
      is = this.getClass().getClassLoader().getResourceAsStream(configFile);
      Configuration config = new XMLConfiguration(is);
      factory = stack.init(config);
      log.info("Stack Configuration successfully loaded.");
      // Log peer details
      MetaData metaData = stack.getMetaData();
      log.info("Local Peer URI: " + metaData.getLocalPeer().getUri());
      Set<ApplicationId> appIds = stack.getMetaData().getLocalPeer().getCommonApplications();
      log.info("Diameter Stack :: Supporting " + appIds.size() + " applications.");
      for (ApplicationId x : appIds) {
        log.info("Diameter Stack :: Common :: " + x);
      }
      is.close();
      Network network = stack.unwrap(Network.class);
      network.addNetworkReqListener(request -> null, ApplicationId.createByAuthAppId(applicationID));
    } catch (Exception e) {
      log.error("Failed to initialize stack", e);
      if (this.stack != null) {
        this.stack.destroy();
      }
      if (is != null) {
        try {
          is.close();
        } catch (IOException e1) {
          e1.printStackTrace();
        }
      }
      return;
    }
    MetaData metaData = stack.getMetaData();
    if (metaData.getStackType() != StackType.TYPE_SERVER || metaData.getMinorVersion() <= 0) {
      stack.destroy();
      if (log.isEnabledFor(org.apache.log4j.Level.ERROR)) {
        log.error("Incorrect driver");
      }
      return;
    }
    try {
      if (log.isInfoEnabled()) {
        log.info("Starting stack");
      }
      stack.start();
      if (log.isInfoEnabled()) {
        log.info("Stack is running.");
      }
    } catch (Exception e) {
      log.error("Failed to start stack", e);
      stack.destroy();
      return;
    }
    if (log.isInfoEnabled()) {
      log.info("Stack initialization successfully completed.");
    }
  }

  private void start() {
    try {
      log.info("Waiting for server to initialize...");
      Thread.sleep(15000); // Wait 15 seconds
      log.info("Creating new session with ID: CC_SESSION_" + System.currentTimeMillis());
      this.session = this.factory.getNewSession("CC_SESSION_" + System.currentTimeMillis());
      log.info("Sending INITIAL CCR to " + serverURI);
      sendCreditControlRequest(1, initialUnits);
    } catch (Exception e) {
      log.error("Failed to start client", e);
    }
  }

  private void sendCreditControlRequest(int requestType, long requestedUnits) {
    try {
      Request request = session.createRequest(commandCode, ApplicationId.createByAuthAppId(applicationID), realmName, serverURI);
      AvpSet avps = request.getAvps();
      avps.addAvp(CC_REQUEST_TYPE, requestType, true, false);
      avps.addAvp(CC_REQUEST_NUMBER, nextRequestNumber++, true, false);
      avps.addAvp(Avp.USER_NAME, userName, true, false, false);
      if (requestType == 1 || requestType == 2) {
        AvpSet rsu = avps.addGroupedAvp(REQUESTED_SERVICE_UNIT, true, false);
        rsu.addAvp(CC_TOTAL_OCTETS, requestedUnits, true, false);
      }
      session.send(request, this);
      dumpMessage(request, true);
    } catch (Exception e) {
      log.error("Error sending request", e);
    }
  }

  @Override
  public void receivedSuccessMessage(Request request, Answer answer) {
    dumpMessage(answer, false);
    try {
      AvpSet answerAvps = answer.getAvps();
      long resultCode = answerAvps.getAvp(Avp.RESULT_CODE).getUnsigned32();
      log.info("Received CCA with Result-Code: " + resultCode);
      if (resultCode == 4010) { // CREDIT_LIMIT_REACHED
        log.warn("Credit limit reached!");
        sendCreditControlRequest(3, 0); // Terminate
        return;
      }
      log.debug("Checking for Granted-Service-Unit in CCA");
      if (answerAvps.getAvp(GRANTED_SERVICE_UNIT) != null) {
        log.debug("Found Granted-Service-Unit, parsing CC-Total-Octets");
        AvpSet gsu = answerAvps.getAvp(GRANTED_SERVICE_UNIT).getGrouped();
        try {
          long grantedUnits = gsu.getAvp(CC_TOTAL_OCTETS).getUnsigned32();
          log.info("Granted units: " + grantedUnits);
          long ccRequestType = answerAvps.getAvp(CC_REQUEST_TYPE).getUnsigned32();
          if (ccRequestType == 1) { // INITIAL
            Thread.sleep(2000);
            sendCreditControlRequest(2, updateUnits);
          } else if (ccRequestType == 2) { // UPDATE
            Thread.sleep(2000);
            sendCreditControlRequest(3, 0);
          } else if (ccRequestType == 3) { // TERMINATE
            session.release();
            session = null;
          }
        } catch (AvpDataException e) {
          log.error("Failed to parse CC-Total-Octets", e);
          sendCreditControlRequest(3, 0); // Terminate on error
        }
      } else {
        log.warn("No Granted-Service-Unit found in CCA");
        sendCreditControlRequest(3, 0); // Terminate on error
      }
    } catch (Exception e) {
      log.error("Error processing answer", e);
      sendCreditControlRequest(3, 0); // Terminate on error
    }
  }

  @Override
  public void timeoutExpired(Request request) {
    log.warn("Request timed out: " + request.getCommandCode());
    try {
      if (nextRequestNumber <= 2) {
        log.info("Retrying request with CC-Request-Type: " + (nextRequestNumber == 1 ? 1 : 2));
        sendCreditControlRequest(nextRequestNumber == 1 ? 1 : 2, nextRequestNumber == 1 ? initialUnits : updateUnits);
      } else {
        sendCreditControlRequest(3, 0);
      }
    } catch (Exception e) {
      log.error("Failed to send request", e);
      if (session != null) {
        session.release();
        session = null;
      }
    }
  }

  private void dumpMessage(Message message, boolean sending) {
    if (log.isInfoEnabled()) {
      log.info((sending ? "Sending " : "Received ") + (message.isRequest() ? "Request: " : "Answer: ") + message.getCommandCode() + "\nE2E:"
              + message.getEndToEndIdentifier() + "\nHBH:" + message.getHopByHopIdentifier() + "\nAppID:" + message.getApplicationId());
      log.info("AVPS[" + message.getAvps().size() + "]: \n");
      try {
        printAvps(message.getAvps());
      } catch (AvpDataException e) {
        e.printStackTrace();
      }
    }
  }

  private void printAvps(AvpSet avpSet) throws AvpDataException {
    printAvpsAux(avpSet, 0);
  }

  private void printAvpsAux(AvpSet avpSet, int level) throws AvpDataException {
    String prefix = "                      ".substring(0, level * 2);
    for (Avp avp : avpSet) {
      AvpRepresentation avpRep = AvpDictionary.INSTANCE.getAvp(avp.getCode(), avp.getVendorId());
      if (avpRep != null && avpRep.getType().equals("Grouped")) {
        log.info(prefix + "<avp name=\"" + avpRep.getName() + "\" code=\"" + avp.getCode() + "\" vendor=\"" + avp.getVendorId() + "\">");
        printAvpsAux(avp.getGrouped(), level + 1);
        log.info(prefix + "</avp>");
      } else if (avpRep != null) {
        String value = "";
        if (avpRep.getType().equals("Integer32"))
          value = String.valueOf(avp.getInteger32());
        else if (avpRep.getType().equals("Integer64") || avpRep.getType().equals("Unsigned64"))
          value = String.valueOf(avp.getInteger64());
        else if (avpRep.getType().equals("Unsigned32"))
          value = String.valueOf(avp.getUnsigned32());
        else if (avpRep.getType().equals("Float32"))
          value = String.valueOf(avp.getFloat32());
        else
          value = new String(avp.getOctetString(), StandardCharsets.UTF_8);
        log.info(prefix + "<avp name=\"" + avpRep.getName() + "\" code=\"" + avp.getCode() + "\" vendor=\"" + avp.getVendorId()
                + "\" value=\"" + value + "\" />");
      }
    }
  }

  public static void main(String[] args) {
    ExampleClient client = new ExampleClient();
    client.initStack();
    client.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (client.session != null) {
        client.session.release();
        log.info("Session released");
      }
      if (client.stack != null) {
        client.stack.destroy();
        log.info("Stack destroyed");
      }
    }));
  }
}