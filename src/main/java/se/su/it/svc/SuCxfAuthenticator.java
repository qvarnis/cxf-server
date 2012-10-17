package se.su.it.svc;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.authentication.SpnegoAuthenticator;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.log.Log;

import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.eclipse.jetty.webapp.WebAppContext;
import org.spocp.client.SPOCPConnection;
import org.spocp.client.SPOCPConnectionFactory;
import org.spocp.client.SPOCPResult;
import org.spocp.client.SPOCPConnectionFactoryImpl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Enumeration;


public class SuCxfAuthenticator extends SpnegoAuthenticator {
  private WebAppContext myContext = null;

  public SuCxfAuthenticator(WebAppContext context) {
    super();
    myContext = context;
  }



  public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;

    String header = req.getHeader(HttpHeaders.AUTHORIZATION);

    if (!mandatory) {
      return _deferred;
    }

    // check to see if we have authorization headers required to continue
    if (header == null) {
      try {
        if(((HttpServletRequest) request).getQueryString() != null && ((HttpServletRequest) request).getQueryString().equalsIgnoreCase("wsdl")) {
          //Skip authentication for wsdl requests
          return _deferred;
        }
        if (_deferred.isDeferred(res)) {
          return Authentication.UNAUTHENTICATED;
        }

        Log.debug("SpengoAuthenticator: sending challenge");
        res.setHeader(HttpHeaders.WWW_AUTHENTICATE, HttpHeaders.NEGOTIATE);
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return Authentication.SEND_CONTINUE;
      } catch (IOException ioe) {
        throw new ServerAuthException(ioe);
      }
    } else if (header != null && header.startsWith(HttpHeaders.NEGOTIATE)) {
      String spnegoToken = header.substring(10);

      UserIdentity user = _loginService.login(null, spnegoToken);

      if (user != null) {

        if(checkRole(user.getUserPrincipal().getName(), ((HttpServletRequest) request).getRequestURI()))
        {
          Log.info(user.getUserPrincipal().getName() + " Authenticated!");
          return new UserAuthentication(getAuthMethod(), user);
        }
        else {
          try{
            ((HttpServletResponse) response).setHeader(HttpHeaders.WWW_AUTHENTICATE, "realm=\"" + _loginService.getName() + '"');
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return Authentication.SEND_CONTINUE;
          } catch (Exception e) {
            e.printStackTrace();
            return Authentication.SEND_CONTINUE;
          }
        }
      }
    }

    return Authentication.UNAUTHENTICATED;
  }

  protected boolean checkRole(String uid, String rURI) {
    boolean ok = false;
    String trueUid = uid.replaceAll("/.*$", "");
    trueUid = trueUid.replaceAll("@.*$", "");

    String theClass = "se.su.it.svc." + rURI.replaceAll("/", "");
    String role = "";
    try {
      Class annoClass = this.myContext.getClassLoader().loadClass(theClass);
      Annotation[] annotations = annoClass.getAnnotations();
      for (Annotation annotation : annotations) {
        if (annotation.annotationType().getName().equalsIgnoreCase("se.su.it.svc.annotations.SuCxfSvcSpocpRole")) {
          //role = ((SuCxfSvcSpocpRole)annotation).role();
          Method[] methods = annotation.getClass().getMethods();
          for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getName() == "role") {
              role = (String) m.invoke(annotation, null);
              Log.debug("Using SPOCP Role: " + role);
              break;
            }
          }
        }
      }
    } catch (Exception e) {
      Log.warn("Could not figure out class from request URI:" + rURI + ". Faulty classname:" + theClass + ". Exception: " + e.getMessage());
      e.printStackTrace();
      return ok;
    }
    if (role != null && role.length() > 0) {
      SPOCPConnection spocp = null;
      SPOCPConnectionFactoryImpl impl = new SPOCPConnectionFactoryImpl();
      impl.setPort(4751);
      impl.setServer("spocp.su.se");
      try {
        SPOCPConnectionFactory factory = impl;
        spocp = factory.getConnection();
        if (spocp != null) {
          String q = "(j2ee-role (identity (uid " + trueUid + ") (realm SU.SE)) (role " + role + "))";
          SPOCPResult res = spocp.query("/", q);
          ok = res.getResultCode() == SPOCPResult.SPOCP_SUCCESS;
        }
      } catch (Exception ex) {
        Log.warn("Could not check SPOCP Role: " + role + ". Error was: " + ex.getMessage());
        ex.printStackTrace();
      } finally {
        try {
          spocp.logout();
        } catch (Exception ignore) {
        }
      }
      try {
        spocp.logout();
      } catch (Exception ignore) {
      }
    } else {
      Log.info("No SPOCP Role authentication for: " + theClass + ". Call will be let through.");
      return true;
    }
    return (ok);
  }

}