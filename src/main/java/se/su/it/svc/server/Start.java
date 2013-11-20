/*
 * Copyright (c) 2013, IT Services, Stockholm University
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of Stockholm University nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package se.su.it.svc.server;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.su.it.svc.server.filter.FilterHandler;
import se.su.it.svc.server.log.CommonRequestLog;
import se.su.it.svc.server.security.SpnegoAndKrb5LoginService;
import se.su.it.svc.server.security.SuCxfAuthenticator;

import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;

public abstract class Start {
  static final Logger LOG = LoggerFactory.getLogger(Start.class);

  public static final String DEFAULT_SERVER_PREFIX = "cxf-server.";

  public static final String PORT_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "http.port";
  public static final String BIND_ADDRESS_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "bind.address";
  public static final String SSL_ENABLED_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "ssl.enabled";
  public static final String SSL_KEYSTORE_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "ssl.keystore";
  public static final String SSL_PASSWORD_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "ssl.password";
  public static final String SPNEGO_CONFIG_FILE_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "spnego.conf";
  public static final String SPNEGO_REALM_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "spnego.realm";
  public static final String SPNEGO_KDC_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "spnego.kdc";
  public static final String SPNEGO_PROPERTIES_PROPERTY_KEY = DEFAULT_SERVER_PREFIX + "spnego.properties";

  private static final ArrayList<String> MANDATORY_PROPERTIES = new ArrayList<String>() {{
    add(PORT_PROPERTY_KEY);
    add(BIND_ADDRESS_PROPERTY_KEY);
    add(SSL_ENABLED_PROPERTY_KEY);
    add(SPNEGO_CONFIG_FILE_PROPERTY_KEY);
    add(SPNEGO_REALM_PROPERTY_KEY);
    add(SPNEGO_KDC_PROPERTY_KEY);
    add(SPNEGO_PROPERTIES_PROPERTY_KEY);
  }};

  private static final Map<String, List<String>> MANDATORY_DEPENDENCIES = new HashMap<String, List<String>>() {{
    put(SSL_ENABLED_PROPERTY_KEY, new LinkedList<String>() {{
      add(SSL_KEYSTORE_PROPERTY_KEY);
      add(SSL_PASSWORD_PROPERTY_KEY);
    }});
  }};

  public static synchronized void start(Properties config) {

    checkDefinedConfigFileProperties(config);

    int httpPort = Integer.parseInt(config.getProperty(PORT_PROPERTY_KEY).trim());
    String jettyBindAddress = config.getProperty(BIND_ADDRESS_PROPERTY_KEY);

    // extracting the config properties for ssl setup
    boolean sslEnabled = Boolean.parseBoolean(config.getProperty(SSL_ENABLED_PROPERTY_KEY));
    String sslKeystore = config.getProperty(SSL_KEYSTORE_PROPERTY_KEY);
    String sslPassword = config.getProperty(SSL_PASSWORD_PROPERTY_KEY);

    //extracting the config for the spnegp setup
    String spnegoConfigFileName = config.getProperty(SPNEGO_CONFIG_FILE_PROPERTY_KEY);
    String spnegoRealm = config.getProperty(SPNEGO_REALM_PROPERTY_KEY);
    String spnegoKdc = config.getProperty(SPNEGO_KDC_PROPERTY_KEY);
    String spnegoPropertiesFileName = config.getProperty(SPNEGO_PROPERTIES_PROPERTY_KEY);

    try {

      org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server();

      if (sslEnabled) {
        SslSocketConnector connector = new SslSocketConnector();

        connector.setPort(httpPort);
        if (jettyBindAddress != null && jettyBindAddress.length() > 0) {
          connector.setHost(jettyBindAddress);
        }
        connector.setKeystore(sslKeystore);
        connector.setPassword(sslPassword);

        server.setConnectors(new Connector[]{connector});
      } else {
        SocketConnector connector = new SocketConnector();
        connector.setPort(httpPort);
        if (jettyBindAddress != null && jettyBindAddress.length() > 0) {
          connector.setHost(jettyBindAddress);
        }
        server.setConnectors(new Connector[]{connector});
      }

      WebAppContext context = new WebAppContext();
      context.addSystemClass("se.su.it.svc.server");
      context.addSystemClass("org.spocp.jspocp");
      context.addSystemClass("org.apache.cxf");
      context.setServer(server);
      context.setContextPath("/");

      ProtectionDomain protectionDomain = Start.class.getProtectionDomain();
      URL location = protectionDomain.getCodeSource().getLocation();
      context.setWar(location.toExternalForm());

      RequestLogHandler requestLogHandler = new RequestLogHandler();
      FilterHandler fh = new FilterHandler();

      HandlerList handlers = new HandlerList();
      handlers.setHandlers(new Handler[]{requestLogHandler, fh, context, new DefaultHandler()});

      server.setHandler(handlers);

      // Setup request logging
      requestLogHandler.setRequestLog(new CommonRequestLog());

      System.setProperty("java.security.krb5.realm", spnegoRealm);
      System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
      System.setProperty("java.security.auth.login.config", "=file:" + spnegoConfigFileName);
      System.setProperty("java.security.krb5.kdc", spnegoKdc);

      Properties spnegoProperties = new Properties();
      Resource resource = Resource.newResource(spnegoPropertiesFileName);
      spnegoProperties.load(resource.getInputStream());

      SpnegoAndKrb5LoginService loginService = new SpnegoAndKrb5LoginService(spnegoRealm, spnegoProperties.getProperty("targetName"));
      context.getSecurityHandler().setLoginService(loginService);

      SuCxfAuthenticator authenticator = new SuCxfAuthenticator();
      context.getSecurityHandler().setAuthenticator(authenticator);

      server.start();
      LOG.info("Server ready...");
      server.join();

    } catch (Exception ex) {
      LOG.error("Server startup failed.", ex);
    }
  }

  private static boolean checkDefinedConfigFileProperties(Properties properties) {
    List<String> notFoundList = new ArrayList<String>();

    for (String mandatoryProperty : MANDATORY_PROPERTIES) {
      if (MANDATORY_DEPENDENCIES.containsKey(mandatoryProperty)) {

        /** See if the property is actually in the config file. */
        if (properties.getProperty(mandatoryProperty) == null) {
          notFoundList.add(mandatoryProperty);
          continue;
        }

        /** If the property is set we check if the feature is enabled */
        boolean functionEnabled = Boolean.parseBoolean(properties.getProperty(mandatoryProperty));

        /** If the feature is enabled we check if the mandatory dependencies for the features are set */
        if (functionEnabled) {
          List<String> dependencies = MANDATORY_DEPENDENCIES.get(mandatoryProperty);
          for (String dep : dependencies) {
            if (properties.get(dep) == null) {
              notFoundList.add(dep);
            }
          }
        }
      } else {
        if (properties.get(mandatoryProperty) == null) {
          notFoundList.add(mandatoryProperty);
        }
      }
    }

    if (notFoundList.size() <= 0) {
      return true;
    }

    for (String notFound : notFoundList) {
      LOG.error("Property <" + notFound + ">   ...not found");
    }

    // End check for mandatory properties
    LOG.error("Quitting because mandatory properties was missing...");
    return false;  //To change body of created methods use File | Settings | File Templates.
  }

}