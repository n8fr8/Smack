/**
 *
 * Copyright 2003-2007 Jive Software.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smack;

import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.sasl.SASLAnonymous;
import org.jivesoftware.smack.sasl.SASLErrorException;
import org.jivesoftware.smack.sasl.SASLMechanism;
import org.jivesoftware.smack.sasl.packet.SaslStanzas.SASLFailure;

import javax.security.auth.callback.CallbackHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * <p>This class is responsible authenticating the user using SASL, binding the resource
 * to the connection and establishing a session with the server.</p>
 *
 * <p>Once TLS has been negotiated (i.e. the connection has been secured) it is possible to
 * register with the server or authenticate using SASL. If the
 * server supports SASL then Smack will try to authenticate using SASL..</p>
 *
 * <p>The server may support many SASL mechanisms to use for authenticating. Out of the box
 * Smack provides several SASL mechanisms, but it is possible to register new SASL Mechanisms. Use
 * {@link #registerSASLMechanism(SASLMechanism)} to register a new mechanisms.
 *
 * @see org.jivesoftware.smack.sasl.SASLMechanism
 *
 * @author Gaston Dombiak
 * @author Jay Kline
 */
public class SASLAuthentication {

    private static final Queue<SASLMechanism> REGISTERED_MECHANISMS = new PriorityQueue<SASLMechanism>();


    /**
     * Registers a new SASL mechanism
     *
     * @param mechanism a SASLMechanism subclass.
     */
    public static synchronized void registerSASLMechanism(SASLMechanism mechanism)  {
        REGISTERED_MECHANISMS.add(mechanism);
    }

    /**
     * Returns the registered SASLMechanism sorted by the level of preference.
     *
     * @return the registered SASLMechanism sorted by the level of preference.
     */
    public static synchronized Map<String, String> getRegisterdSASLMechanisms() {
        Map<String, String> answer = new HashMap<String,String>();
        for (SASLMechanism mechanism : REGISTERED_MECHANISMS) {
            answer.put(mechanism.getClass().getName(), mechanism.getName());
        }
        return answer;
    }

    /**
     * Unregister a SASLMechanism by it's full class name. For example
     * "org.jivesoftware.smack.sasl.javax.SASLCramMD5Mechanism".
     * 
     * @param clazz the SASLMechanism class's name
     * @return true if the given SASLMechanism was removed, false otherwise
     */
    public static synchronized boolean unregisterSASLMechanism(String clazz) {
        Iterator<SASLMechanism> it = REGISTERED_MECHANISMS.iterator();
        while (it.hasNext()) {
            SASLMechanism mechanism = it.next();
            if (mechanism.getClass().getName().equals(clazz)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private final AbstractXMPPConnection connection;
    private Collection<String> serverMechanisms = new ArrayList<String>();
    private SASLMechanism currentMechanism = null;

    /**
     * Boolean indicating if SASL negotiation has finished and was successful.
     */
    private boolean saslNegotiated;

    /**
     * The SASL related error condition if there was one provided by the server.
     */
    private SASLFailure saslFailure;

    SASLAuthentication(AbstractXMPPConnection connection) {
        this.connection = connection;
        this.init();
    }

    /**
     * Returns true if the server offered ANONYMOUS SASL as a way to authenticate users.
     *
     * @return true if the server offered ANONYMOUS SASL as a way to authenticate users.
     */
    public boolean hasAnonymousAuthentication() {
        return serverMechanisms.contains("ANONYMOUS");
    }

    /**
     * Returns true if the server offered SASL authentication besides ANONYMOUS SASL.
     *
     * @return true if the server offered SASL authentication besides ANONYMOUS SASL.
     */
    public boolean hasNonAnonymousAuthentication() {
        return !serverMechanisms.isEmpty() && (serverMechanisms.size() != 1 || !hasAnonymousAuthentication());
    }

    /**
     * Performs SASL authentication of the specified user. If SASL authentication was successful
     * then resource binding and session establishment will be performed. This method will return
     * the full JID provided by the server while binding a resource to the connection.<p>
     *
     * The server may assign a full JID with a username or resource different than the requested
     * by this method.
     *
     * @param resource the desired resource.
     * @param cbh the CallbackHandler used to get information from the user
     * @throws IOException 
     * @throws XMPPErrorException 
     * @throws SASLErrorException 
     * @throws SmackException 
     */
    public void authenticate(String resource, CallbackHandler cbh) throws IOException,
                    XMPPErrorException, SASLErrorException, SmackException {
        // Locate the SASLMechanism to use
        SASLMechanism selectedMechanism = null;
        Iterator<SASLMechanism> it = REGISTERED_MECHANISMS.iterator();
        // Iterate in SASL Priority order over registered mechanisms
        while (it.hasNext()) {
            SASLMechanism mechanism = it.next();
            if (serverMechanisms.contains(mechanism.getName())) {
                // Create a new instance of the SASLMechanism for every authentication attempt.
                selectedMechanism = mechanism.instanceForAuthentication(connection);
                break;
            }
        }
        if (selectedMechanism != null) {
            currentMechanism = selectedMechanism;
            synchronized (this) {
                currentMechanism.authenticate(connection.getHost(), connection.getServiceName(), cbh);
                try {
                    // Wait until SASL negotiation finishes
                    wait(connection.getPacketReplyTimeout());
                }
                catch (InterruptedException e) {
                    // Ignore
                }
            }

            if (saslFailure != null) {
                // SASL authentication failed and the server may have closed the connection
                // so throw an exception
                throw new SASLErrorException(selectedMechanism.getName(), saslFailure);
            }

            if (!saslNegotiated) {
                throw new NoResponseException();
            }
        }
        else {
            throw new SmackException(
                            "SASL Authentication failed. No known authentication mechanisims.");
        }
    }

    /**
     * Performs SASL authentication of the specified user. If SASL authentication was successful
     * then resource binding and session establishment will be performed. This method will return
     * the full JID provided by the server while binding a resource to the connection.<p>
     *
     * The server may assign a full JID with a username or resource different than the requested
     * by this method.
     *
     * @param username the username that is authenticating with the server.
     * @param password the password to send to the server.
     * @param resource the desired resource.
     * @throws XMPPErrorException 
     * @throws SASLErrorException 
     * @throws IOException 
     * @throws SmackException 
     */
    public void authenticate(String username, String password, String resource)
                    throws XMPPErrorException, SASLErrorException, IOException,
                    SmackException {
        // Locate the SASLMechanism to use
        SASLMechanism selectedMechanism = null;
        Iterator<SASLMechanism> it = REGISTERED_MECHANISMS.iterator();
        // Iterate in SASL Priority order over registered mechanisms
        while (it.hasNext()) {
            SASLMechanism mechanism = it.next();
            if (serverMechanisms.contains(mechanism.getName())) {
                // Create a new instance of the SASLMechanism for every authentication attempt.
                selectedMechanism = mechanism.instanceForAuthentication(connection);
                break;
            }
        }
        if (selectedMechanism != null) {
            currentMechanism = selectedMechanism;

            synchronized (this) {
                currentMechanism.authenticate(username, connection.getHost(),
                                connection.getServiceName(), password);

                try {
                    // Wait until SASL negotiation finishes
                    wait(connection.getPacketReplyTimeout());
                }
                catch (InterruptedException e) {
                    // Ignore
                }

            }

            if (saslFailure != null) {
                // SASL authentication failed and the server may have closed the connection
                // so throw an exception
                throw new SASLErrorException(selectedMechanism.getName(), saslFailure);
            }

            if (!saslNegotiated) {
                throw new NoResponseException();
            }
        }
        else {
            throw new SmackException(
                            "SASL Authentication failed. No known authentication mechanisims.");
        }
    }

    /**
     * Performs ANONYMOUS SASL authentication. If SASL authentication was successful
     * then resource binding and session establishment will be performed. This method will return
     * the full JID provided by the server while binding a resource to the connection.<p>
     *
     * The server will assign a full JID with a randomly generated resource and possibly with
     * no username.
     *
     * @throws SASLErrorException 
     * @throws XMPPErrorException if an error occures while authenticating.
     * @throws SmackException if there was no response from the server.
     */
    public void authenticateAnonymously() throws SASLErrorException,
                    SmackException, XMPPErrorException {
        currentMechanism = (new SASLAnonymous()).instanceForAuthentication(connection);

        // Wait until SASL negotiation finishes
        synchronized (this) {
            currentMechanism.authenticate(null, null, null, "");
            try {
                wait(connection.getPacketReplyTimeout());
            }
            catch (InterruptedException e) {
                // Ignore
            }
        }

        if (saslFailure != null) {
            // SASL authentication failed and the server may have closed the connection
            // so throw an exception
            throw new SASLErrorException(currentMechanism.toString(), saslFailure);
        }

        if (!saslNegotiated) {
            throw new NoResponseException();
        }
    }

    /**
     * Sets the available SASL mechanism reported by the server. The server will report the
     * available SASL mechanism once the TLS negotiation was successful. This information is
     * stored and will be used when doing the authentication for logging in the user.
     *
     * @param mechanisms collection of strings with the available SASL mechanism reported
     *                   by the server.
     */
    public void setAvailableSASLMethods(Collection<String> mechanisms) {
        this.serverMechanisms = mechanisms;
    }

    /**
     * The server is challenging the SASL authentication we just sent. Forward the challenge
     * to the current SASLMechanism we are using. The SASLMechanism will send a response to
     * the server. The length of the challenge-response sequence varies according to the
     * SASLMechanism in use.
     *
     * @param challenge a base64 encoded string representing the challenge.
     * @throws Exception
     * @throws NotConnectedException 
     */
    public void challengeReceived(String challenge) throws Exception, NotConnectedException {
        currentMechanism.challengeReceived(challenge);
    }

    /**
     * Notification message saying that SASL authentication was successful. The next step
     * would be to bind the resource.
     */
    public void authenticated() {
        saslNegotiated = true;
        // Wake up the thread that is waiting in the #authenticate method
        synchronized (this) {
            notify();
        }
    }

    /**
     * Notification message saying that SASL authentication has failed. The server may have
     * closed the connection depending on the number of possible retries.
     * 
     * @param saslFailure the SASL failure as reported by the server
     * @see <a href="https://tools.ietf.org/html/rfc6120#section-6.5">RFC6120 6.5</a>
     */
    public void authenticationFailed(SASLFailure saslFailure) {
        this.saslFailure = saslFailure;
        // Wake up the thread that is waiting in the #authenticate method
        synchronized (this) {
            notify();
        }
    }

    
    /**
     * Initializes the internal state in order to be able to be reused. The authentication
     * is used by the connection at the first login and then reused after the connection
     * is disconnected and then reconnected.
     */
    protected void init() {
        saslNegotiated = false;
        saslFailure = null;
    }
}
