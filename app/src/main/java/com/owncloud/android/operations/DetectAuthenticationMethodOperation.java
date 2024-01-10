/**
 * ownCloud Android client application
 *
 *   @author David A. Velasco
 *   Copyright (C) 2015 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.owncloud.android.operations;

import android.net.Uri;
import android.text.TextUtils;

import com.nextcloud.common.NextcloudClient;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.ExistenceCheckRemoteOperation;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpStatus;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Operation to find out what authentication method requires the server to access files.
 * <p>
 * Basically, tries to access to the root folder without authorization and analyzes the response.
 * <p>
 * When successful, the instance of {@link RemoteOperationResult} passed through
 * {@link com.owncloud.android.lib.common.operations.OnRemoteOperationListener #onRemoteOperationFinish(RemoteOperation,
 * RemoteOperationResult)} returns in {@link RemoteOperationResult#getData()} a value of {@link AuthenticationMethod}.
 */
public class DetectAuthenticationMethodOperation extends RemoteOperation<DetectAuthenticationMethodOperation.AuthenticationMethod> {

    private static final String TAG = DetectAuthenticationMethodOperation.class.getSimpleName();

    /**
     * Performs the operation.
     * <p>
     * Triggers a check of existence on the root folder of the server, granting that the request is not authenticated.
     * <p>
     * Analyzes the result of check to find out what authentication method, if any, is requested by the server.
     */
    @Override
    public RemoteOperationResult<AuthenticationMethod> run(NextcloudClient client) {
        RemoteOperation<Void> operation = new ExistenceCheckRemoteOperation("", false);
        client.setCredentials("");
        client.setFollowRedirects(false);

        // try to access the root folder, following redirections but not SAML SSO redirections
        RemoteOperationResult<Void> existanceCheckResult = operation.execute(client);
        String redirectedLocation = existanceCheckResult.getRedirectedLocation();
        while (!TextUtils.isEmpty(redirectedLocation) && !existanceCheckResult.isIdPRedirection()) {
            client.setBaseUri(Uri.parse(existanceCheckResult.getRedirectedLocation()));
            existanceCheckResult = operation.execute(client);
            redirectedLocation = existanceCheckResult.getRedirectedLocation();
        }

        // analyze response
        AuthenticationMethod authMethod = AuthenticationMethod.UNKNOWN;
        if (existanceCheckResult.getHttpCode() == HttpStatus.SC_UNAUTHORIZED || existanceCheckResult.getHttpCode() == HttpStatus.SC_FORBIDDEN) {
            ArrayList<String> authHeaders = existanceCheckResult.getAuthenticateHeaders();

            for (String header : authHeaders) {
                // currently we only support basic auth
                if (header.toLowerCase(Locale.ROOT).contains("basic")) {
                    authMethod = AuthenticationMethod.BASIC_HTTP_AUTH;
                    break;
                }
            }
            // else - fall back to UNKNOWN

        } else if (existanceCheckResult.isSuccess()) {
            authMethod = AuthenticationMethod.NONE;

        } else if (existanceCheckResult.isIdPRedirection()) {
            authMethod = AuthenticationMethod.SAML_WEB_SSO;
        }
        // else - fall back to UNKNOWN
        Log_OC.d(TAG, "Authentication method found: " + authenticationMethodToString(authMethod));

        boolean isSuccess = authMethod != AuthenticationMethod.UNKNOWN || existanceCheckResult.isSuccess();
        RemoteOperationResult<AuthenticationMethod> result = new RemoteOperationResult<>(isSuccess,
                                                                                         existanceCheckResult.getHttpCode(), existanceCheckResult.getHttpPhrase(), new Header[0]);
        result.setResultData(authMethod);
        return result;  // same result instance, so that other errors
        // can be handled by the caller transparently
    }

    private String authenticationMethodToString(AuthenticationMethod value) {
        return switch (value) {
            case NONE -> "NONE";
            case BASIC_HTTP_AUTH -> "BASIC_HTTP_AUTH";
            case BEARER_TOKEN -> "BEARER_TOKEN";
            case SAML_WEB_SSO -> "SAML_WEB_SSO";
            default -> "UNKNOWN";
        };
    }

    public enum AuthenticationMethod {
        UNKNOWN, NONE, BASIC_HTTP_AUTH, SAML_WEB_SSO, BEARER_TOKEN
    }

}
