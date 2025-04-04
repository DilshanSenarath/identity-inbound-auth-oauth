/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.oidc.session.backchannellogout;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oidc.session.util.OIDCSessionManagementUtil;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/**
 * Used to send logout request.
 */
public class LogoutRequestSender {

    private static final Log LOG = LogFactory.getLog(LogoutRequestSender.class);
    private static ExecutorService threadPool = Executors.newFixedThreadPool(2);
    private static LogoutRequestSender instance = new LogoutRequestSender();
    private static final String LOGOUT_TOKEN = "logout_token";

    private LogoutRequestSender() {

    }

    /**
     * getInstance() method of LogoutRequestSender, as it is a singleton.
     *
     * @return LogoutRequestSender instance
     */
    public static LogoutRequestSender getInstance() {

        return instance;
    }

    /**
     * Sends logout requests to all service providers.
     *
     * @param request
     *
     * @deprecated This method was deprecated to move OIDC SessionParticipantCache to the tenant space.
     * Use {@link #sendLogoutRequests(String, String)} instead.
     */
    @Deprecated
    public void sendLogoutRequests(HttpServletRequest request) {

        Cookie opbsCookie = OIDCSessionManagementUtil.getOPBrowserStateCookie(request);
        if (opbsCookie != null) {
            sendLogoutRequests(opbsCookie.getValue());
        } else {
            LOG.error("No opbscookie exists in the request");
        }
    }

    /**
     * Sends logout requests to all service providers.
     *
     * @param opbsCookieId
     *
     * @deprecated This method was deprecated to move OIDCSessionParticipantCache to the tenant space.
     * Use {@link #sendLogoutRequests(String, String)} instead.
     */
    @Deprecated
    public void sendLogoutRequests(String opbsCookieId) {

        // For backward compatibility, SUPER_TENANT_DOMAIN was added as the cache maintained tenant.
        sendLogoutRequests(opbsCookieId, MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
    }

    /**
     * Sends logout requests to all service providers.
     *
     * @param opbsCookieId OPBS Cookie ID value
     * @param tenantDomain Tenant Domain
     */
    public void sendLogoutRequests(String opbsCookieId, String tenantDomain) {

        Map<String, String> logoutTokenList = getLogoutTokenList(opbsCookieId, tenantDomain);
        if (MapUtils.isNotEmpty(logoutTokenList)) {
            // For each logoutReq, create a new task and submit it to the thread pool.
            for (Map.Entry<String, String> logoutTokenMap : logoutTokenList.entrySet()) {
                String logoutToken = logoutTokenMap.getKey();
                String bcLogoutUrl = logoutTokenMap.getValue();
                LOG.debug("A logoutReqSenderTask will be assigned to the thread pool");
                threadPool.submit(new LogoutReqSenderTask(logoutToken, bcLogoutUrl));
            }
        }
    }

    /**
     * Returns a Map with logout tokens and back-channel logut Url of Service providers.
     *
     * @param opbsCookie OpbsCookie.
     * @return Map with logoutToken, back-channel logout Url.
     */
    private Map<String, String> getLogoutTokenList(String opbsCookie, String tenantDomain) {

        Map<String, String> logoutTokenList = null;
        try {
            DefaultLogoutTokenBuilder logoutTokenBuilder = new DefaultLogoutTokenBuilder();
            logoutTokenList = logoutTokenBuilder.buildLogoutToken(opbsCookie, tenantDomain);
        } catch (IdentityOAuth2Exception e) {
            LOG.error("Error while initializing " + DefaultLogoutTokenBuilder.class, e);
        } catch (InvalidOAuthClientException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error while obtaining logout token list for the obpsCookie: " + opbsCookie +
                                "& tenant domain: " + tenantDomain, e);
            }
        }
        return logoutTokenList;
    }

    /**
     * This class is used to model a single logout request that is being sent to a session participant.
     * It will send the logout req. to the session participant in its 'run' method when this job is
     * submitted to the thread pool.
     */
    private class LogoutReqSenderTask implements Runnable {

        private String logoutToken;
        private String backChannelLogouturl;

        public LogoutReqSenderTask(String logoutToken, String backChannelLogouturl) {

            this.logoutToken = logoutToken;
            this.backChannelLogouturl = backChannelLogouturl;
        }

        @Override
        public void run() {

            if (LOG.isDebugEnabled()) {
                LOG.debug("Starting backchannel logout request to: " + backChannelLogouturl);
            }

            List<NameValuePair> logoutReqParams = new ArrayList<NameValuePair>();
            String hostNameVerificationEnabledProperty =
                    IdentityUtil.getProperty(IdentityConstants.ServerConfig.SLO_HOST_NAME_VERIFICATION_ENABLED);
            boolean isHostNameVerificationEnabled = true;
            if ("false".equalsIgnoreCase(hostNameVerificationEnabledProperty)) {
                isHostNameVerificationEnabled = false;
            }
            try {
                HttpClient httpClient;
                if (!isHostNameVerificationEnabled) {
                    httpClient = HttpClients.custom()
                            .setHostnameVerifier(SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
                            .build();
                } else {
                    httpClient = HttpClients.createDefault();
                }
                logoutReqParams.add(new BasicNameValuePair(LOGOUT_TOKEN, logoutToken));
                HttpPost httpPost = new HttpPost(backChannelLogouturl);
                try {
                    httpPost.setEntity(new UrlEncodedFormEntity(logoutReqParams));
                } catch (UnsupportedEncodingException e) {
                    LOG.error("Error while sending logout token", e);
                }
                HttpResponse response = httpClient.execute(httpPost);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Backchannel logout response: " + response.getStatusLine());
                }

            } catch (IOException e) {
                LOG.error("Error sending logout requests to: " + backChannelLogouturl, e);
            }
        }
    }
}
