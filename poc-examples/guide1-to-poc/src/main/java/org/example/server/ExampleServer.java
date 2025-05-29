package org.example.server;

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
import org.jdiameter.api.InternalException;
import org.jdiameter.api.Message;
import org.jdiameter.api.MetaData;
import org.jdiameter.api.Network;
import org.jdiameter.api.NetworkReqListener;
import org.jdiameter.api.Request;
import org.jdiameter.api.SessionFactory;
import org.jdiameter.api.Stack;
import org.jdiameter.api.StackType;
import org.jdiameter.server.impl.StackImpl;
import org.jdiameter.server.impl.helpers.XMLConfiguration;
import org.mobicents.diameter.dictionary.AvpDictionary;
import org.mobicents.diameter.dictionary.AvpRepresentation;

public class ExampleServer implements NetworkReqListener {
  private static final Logger log = Logger.getLogger(ExampleServer.class);
  static {
    configLog4j();
  }

  private static void configLog4j() {
    InputStream inStreamLog4j = ExampleServer.class.getClassLoader().getResourceAsStream("log4j.properties");
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

  private static final String configFile = "org/example/server/server-jdiameter-config.xml";
  private static final String dictionaryFile = "org/example/client/dictionary.xml";
  private static final String realmName = "exchange.example.org";
  private static final int commandCode = 272;
  private static final long applicationID = 4;
  private static final int CC_REQUEST_TYPE = 416;
  private static final int CC_REQUEST_NUMBER = 415;
  private static final int REQUESTED_SERVICE_UNIT = 437;
  private static final int GRANTED_SERVICE_UNIT = 431;
  private static final int CC_TOTAL_OCTETS = 412;
  private static final int USED_SERVICE_UNIT = 446;

  private long initialQuota;
  private AvpDictionary dictionary = AvpDictionary.INSTANCE;
  private Stack stack;
  private SessionFactory factory;

  public ExampleServer() {
    Properties props = new Properties();
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("server.properties")) {
      if (is != null) {
        props.load(is);
      }
      this.initialQuota = Long.parseLong(props.getProperty("initial.quota", "100"));
    } catch (IOException e) {
      log.error("Failed to load server.properties, using default quota 100", e);
      this.initialQuota = 100;
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
      if (log.isInfoEnabled()) {
        log.info("Stack Configuration successfully loaded.");
      }
      Set<ApplicationId> appIds = stack.getMetaData().getLocalPeer().getCommonApplications();
      log.info("Diameter Stack  :: Supporting " + appIds.size() + " applications.");
      for (ApplicationId x : appIds) {
        log.info("Diameter Stack  :: Common :: " + x);
      }
      is.close();
      Network network = stack.unwrap(Network.class);
      network.addNetworkReqListener(this, ApplicationId.createByAuthAppId(applicationID));
    } catch (Exception e) {
      e.printStackTrace();
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
      e.printStackTrace();
      stack.destroy();
      return;
    }
    if (log.isInfoEnabled()) {
      log.info("Stack initialization successfully completed.");
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

  @Override
  public Answer processRequest(Request request) {
    dumpMessage(request, false);
    try {
      AvpSet requestAvps = request.getAvps();
      long ccRequestType = requestAvps.getAvp(CC_REQUEST_TYPE).getUnsigned32();
      log.info("Processing CCR with CC-Request-Type: " + ccRequestType);
      long ccRequestNumber = requestAvps.getAvp(CC_REQUEST_NUMBER).getUnsigned32();
      String sessionId = request.getSessionId();
      String userId = requestAvps.getAvp(Avp.USER_NAME).getUTF8String();
      Answer answer = null;
      SessionManager.UserSession session = SessionManager.getInstance().getSession(sessionId);
      switch ((int) ccRequestType) {
        case 1: // INITIAL_REQUEST
          if (session != null) {
            log.warn("Session already exists for ID: " + sessionId);
            return request.createAnswer(5005); // DIAMETER_INVALID_AVP_VALUE
          }
          session = SessionManager.getInstance().createSession(sessionId, userId, initialQuota);
          answer = request.createAnswer(2001);
          addCommonAVPs(answer.getAvps());
          answer.getAvps().addAvp(CC_REQUEST_TYPE, ccRequestType, true, false);
          answer.getAvps().addAvp(CC_REQUEST_NUMBER, ccRequestNumber, true, false);
          AvpSet gsu = answer.getAvps().addGroupedAvp(GRANTED_SERVICE_UNIT, true, false);
          gsu.addAvp(CC_TOTAL_OCTETS, initialQuota, true, false);
          log.info("Added Granted-Service-Unit with CC-Total-Octets=" + initialQuota);
          break;
        case 2: // UPDATE_REQUEST
          if (session == null) {
            log.warn("No session found for ID: " + sessionId);
            return request.createAnswer(5001); // DIAMETER_UNABLE_TO_COMPLY
          }
          long requestedUnits = requestAvps.getAvp(REQUESTED_SERVICE_UNIT)
                  .getGrouped().getAvp(CC_TOTAL_OCTETS).getUnsigned32();
          if (session.useQuota(requestedUnits)) {
            answer = request.createAnswer(2001);
            addCommonAVPs(answer.getAvps());
            answer.getAvps().addAvp(CC_REQUEST_TYPE, ccRequestType, true, false);
            answer.getAvps().addAvp(CC_REQUEST_NUMBER, ccRequestNumber, true, false);
            gsu = answer.getAvps().addGroupedAvp(GRANTED_SERVICE_UNIT, true, false);
            gsu.addAvp(CC_TOTAL_OCTETS, requestedUnits, true, false);
            log.info("Added Granted-Service-Unit with CC-Total-Octets=" + requestedUnits);
          } else {
            answer = request.createAnswer(4010); // DIAMETER_CREDIT_LIMIT_REACHED
            addCommonAVPs(answer.getAvps());
            answer.getAvps().addAvp(CC_REQUEST_TYPE, ccRequestType, true, false);
            answer.getAvps().addAvp(CC_REQUEST_NUMBER, ccRequestNumber, true, false);
            long remaining = session.getRemainingQuota();
            if (remaining > 0) {
              gsu = answer.getAvps().addGroupedAvp(GRANTED_SERVICE_UNIT, true, false);
              gsu.addAvp(CC_TOTAL_OCTETS, remaining, true, false);
              log.info("Added Granted-Service-Unit with CC-Total-Octets=" + remaining);
            }
          }
          break;
        case 3: // TERMINATION_REQUEST
          if (session != null) {
            answer = request.createAnswer(2001);
            addCommonAVPs(answer.getAvps());
            answer.getAvps().addAvp(CC_REQUEST_TYPE, ccRequestType, true, false);
            answer.getAvps().addAvp(CC_REQUEST_NUMBER, ccRequestNumber, true, false);
            AvpSet usage = answer.getAvps().addGroupedAvp(USED_SERVICE_UNIT, true, false);
            usage.addAvp(CC_TOTAL_OCTETS, session.getUsedQuota(), true, false);
            log.info("Added Used-Service-Unit with CC-Total-Octets=" + session.getUsedQuota());
            SessionManager.getInstance().terminateSession(sessionId);
          } else {
            log.warn("No session found for termination, ID: " + sessionId);
            answer = request.createAnswer(5001); // DIAMETER_UNABLE_TO_COMPLY
          }
          break;
        default:
          log.error("Invalid CC-Request-Type: " + ccRequestType);
          return request.createAnswer(5012); // DIAMETER_INVALID_AVP_VALUE
      }
      dumpMessage(answer, true);
      return answer;
    } catch (Exception e) {
      log.error("Error processing request", e);
      return request.createAnswer(5001); // DIAMETER_UNABLE_TO_COMPLY
    }
  }

  private void addCommonAVPs(AvpSet avps) throws AvpDataException {
    avps.addAvp(Avp.ORIGIN_HOST, stack.getMetaData().getLocalPeer().getUri().getFQDN(), true, false, true);
    avps.addAvp(Avp.ORIGIN_REALM, stack.getMetaData().getLocalPeer().getRealmName(), true, false, true);
    avps.addAvp(Avp.RESULT_CODE, 2001L, true, false, true); // DIAMETER_SUCCESS
  }

  public static void main(String[] args) {
    ExampleServer server = new ExampleServer();
    server.initStack();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (server.stack != null) {
        server.stack.destroy();
        log.info("Stack destroyed");
      }
    }));
  }
}