/**
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 **/

package org.mule.module.gcm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.codehaus.jackson.type.TypeReference;
import org.mule.api.MessagingException;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Module;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.Optional;
import org.mule.api.context.MuleContextAware;
import org.mule.config.i18n.MessageFactory;
import org.mule.module.gcm.model.Data;
import org.mule.module.gcm.model.GcmRequest;
import org.mule.module.gcm.model.GcmResponse;
import org.mule.module.gcm.model.NotificationRequest;
import org.mule.module.gcm.model.NotificationRequest.Operation;
import org.mule.module.gcm.model.NotificationResponse;
import org.mule.transformer.types.MimeTypes;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
import org.mule.util.MapUtils;
import org.mule.util.NumberUtils;
import org.mule.util.StringUtils;

/**
 * Mule connector for Google Cloud Messaging (HTTP API).
 * <p/>
 * {@sample.xml ../../../doc/gcm-connector.xml.sample gcm:config}
 * 
 * @author MuleSoft, Inc.
 */
@Module(name = "gcm", schemaVersion = "1.0", friendlyName = "GCM (HTTP)", minMuleVersion = "3.4", description = "Google Cloud Messaging Connector")
public class GcmConnector extends AbstractGcmConnector implements MuleContextAware
{
    public static final String GCM_SEND_URI = "https://android.googleapis.com/gcm/send";
    public static final String GCM_NOTIFICATION_URI = "https://android.googleapis.com/gcm/notification";

    private static final TypeReference<GcmResponse> GCM_RESPONSE_TYPE_REFERENCE = new TypeReference<GcmResponse>()
    {
        // NOOP
    };
    private static final TypeReference<NotificationResponse> NOTIFICATION_RESPONSE_TYPE_REFERENCE = new TypeReference<NotificationResponse>()
    {
        // NOOP
    };

    /**
     * The project ID (aka sender ID).
     */
    @Configurable
    private String projectId;

    /**
     * The connector to use to reach Neo4j: configure only if there is more than one HTTP/HTTPS
     * connector active in your Mule application.
     */
    @Configurable
    @Optional
    private org.mule.api.transport.Connector connector;

    private MuleContext muleContext;

    /**
     * Send a message using the HTTP API.
     * <p/>
     * {@sample.xml ../../../doc/gcm-connector.xml.sample gcm:send-message-no-data}
     * <p/>
     * {@sample.xml ../../../doc/gcm-connector.xml.sample gcm:send-message-with-data}
     * 
     * @param registrationIds the list of devices (registration IDs) receiving the message.
     * @param notificationKey a string that maps a single user to multiple registration IDs
     *            associated with that user.
     * @param notificationKeyName a name that is unique to a given user.
     * @param collapseKey an arbitrary string that is used to collapse a group of like messages when
     *            the device is offline, so that only the last message gets sent to the client.
     * @param data the key-value pairs of the message's payload data.
     * @param delayWhileIdle indicates that the message should not be sent immediately if the device
     *            is idle.
     * @param timeToLiveSeconds how long (in seconds) the message should be kept on GCM storage if
     *            the device is offline.
     * @param restrictedPackageName a string containing the package name of your application.
     * @param dryRun allows developers to test their request without actually sending a message.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @return a {@link GcmResponse} instance.
     * @throws Exception thrown in case anything goes wrong with the operation.
     */
    @Processor
    @Inject
    public GcmResponse sendMessage(final List<String> registrationIds,
                                   @Optional final String notificationKey,
                                   @Optional final String notificationKeyName,
                                   @Optional final String collapseKey,
                                   @Optional final Map<String, Object> data,
                                   @Optional @Default("false") final boolean delayWhileIdle,
                                   @Optional @Default("2419200") final int timeToLiveSeconds,
                                   @Optional final String restrictedPackageName,
                                   @Optional @Default("false") final boolean dryRun,
                                   final MuleEvent muleEvent) throws Exception
    {
        final GcmRequest gcmRequest = new GcmRequest();
        gcmRequest.getRegistrationIds().addAll(registrationIds);
        gcmRequest.setNotificationKey(notificationKeyName);
        gcmRequest.setNotificationKeyName(notificationKeyName);
        gcmRequest.setCollapseKey(collapseKey);
        gcmRequest.setDelayWhileIdle(delayWhileIdle);
        gcmRequest.setTimeToLive(timeToLiveSeconds);
        gcmRequest.setRestrictedPackageName(restrictedPackageName);
        gcmRequest.setDryRun(dryRun);

        if (MapUtils.isNotEmpty(data))
        {
            final Data gcmData = new Data();
            gcmData.getAdditionalProperties().putAll(data);
            gcmRequest.setData(gcmData);
        }

        final String uri = GCM_SEND_URI + (connector != null ? "?connector=" + connector.getName() : "");
        return httpPostJson(gcmRequest, uri, GCM_RESPONSE_TYPE_REFERENCE, muleEvent);
    }

    /**
     * Create a new notification key.
     * <p/>
     * {@sample.xml ../../../doc/gcm-connector.xml.sample gcm:create-notification-key}
     * 
     * @param notificationKeyName a name that is unique to a given user.
     * @param registrationIds the list of devices (registration IDs) to associate with the key.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @return the created notification key as a {@link String}.
     * @throws Exception thrown in case anything goes wrong with the operation.
     */
    @Processor
    @Inject
    public String createNotificationKey(final String notificationKeyName,
                                        final List<String> registrationIds,
                                        final MuleEvent muleEvent) throws Exception
    {
        return runNotificationRegistrationsAction(Operation.CREATE, null, notificationKeyName,
            registrationIds, muleEvent);
    }

    /**
     * Add registrations to an existing key.
     * <p/>
     * {@sample.xml ../../../doc/gcm-connector.xml.sample gcm:add-notification-registrations}
     * 
     * @param notificationKey a string that maps a single user to multiple registration IDs
     *            associated with that user.
     * @param notificationKeyName a name that is unique to a given user.
     * @param registrationIds the list of devices (registration IDs) to associate with the key.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @throws Exception thrown in case anything goes wrong with the operation.
     */
    @Processor
    @Inject
    public void addNotificationRegistrations(final String notificationKey,
                                             final String notificationKeyName,
                                             final List<String> registrationIds,
                                             final MuleEvent muleEvent) throws Exception
    {
        runNotificationRegistrationsAction(Operation.ADD, notificationKey, notificationKeyName,
            registrationIds, muleEvent);
    }

    /**
     * Remove registrations from an existing key.
     * <p/>
     * {@sample.xml ../../../doc/gcm-connector.xml.sample gcm:remove-notification-registrations}
     * 
     * @param notificationKey a string that maps a single user to multiple registration IDs
     *            associated with that user.
     * @param notificationKeyName a name that is unique to a given user.
     * @param registrationIds the list of devices (registration IDs) to associate with the key.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @throws Exception thrown in case anything goes wrong with the operation.
     */
    @Processor
    @Inject
    public void removeNotificationRegistrations(final String notificationKey,
                                                final String notificationKeyName,
                                                final List<String> registrationIds,
                                                final MuleEvent muleEvent) throws Exception
    {
        runNotificationRegistrationsAction(Operation.REMOVE, notificationKey, notificationKeyName,
            registrationIds, muleEvent);
    }

    public String runNotificationRegistrationsAction(final Operation operation,
                                                     final String notificationKey,
                                                     final String notificationKeyName,
                                                     final List<String> registrationIds,
                                                     final MuleEvent muleEvent) throws Exception
    {
        final NotificationRequest notificationRequest = new NotificationRequest();
        notificationRequest.setOperation(operation);
        notificationRequest.setNotificationKeyName(notificationKey);
        notificationRequest.setNotificationKeyName(notificationKeyName);
        notificationRequest.getRegistrationIds().addAll(registrationIds);

        final NotificationResponse notificationResponse = httpPostJson(notificationRequest,
            GCM_NOTIFICATION_URI, NOTIFICATION_RESPONSE_TYPE_REFERENCE, muleEvent);

        return notificationResponse.getNotificationKey();
    }

    private <T> T httpPostJson(final Object requestEntity,
                               final String uri,
                               final TypeReference<T> responseTypeReference,
                               final MuleEvent muleEvent) throws MuleException, MessagingException, Exception
    {
        final String requestJson = serializeEntityToJson(requestEntity);

        final Map<String, Object> requestProperties = new HashMap<String, Object>();
        requestProperties.put(HttpConstants.HEADER_CONTENT_TYPE, MimeTypes.JSON);
        requestProperties.put(HttpConstants.HEADER_AUTHORIZATION, "key=" + getApiKey());
        if (StringUtils.isNotBlank(getProjectId()))
        {
            requestProperties.put("project_id", getProjectId());
        }

        final MuleMessage response = muleContext.getClient().send(uri, requestJson, requestProperties,
            muleEvent.getTimeout());

        final Object responseStatusCode = response.getInboundProperty(HttpConnector.HTTP_STATUS_PROPERTY);
        if (NumberUtils.toInt(responseStatusCode) != HttpConstants.SC_OK)
        {
            throw new MessagingException(MessageFactory.createStaticMessage("Received status code: "
                                                                            + responseStatusCode
                                                                            + ", entity:"
                                                                            + response.getPayloadAsString()),
                muleEvent);
        }

        return deserializeJsonToEntity(responseTypeReference, response);
    }

    @Override
    public void setMuleContext(final MuleContext muleContext)
    {
        this.muleContext = muleContext;
    }

    public org.mule.api.transport.Connector getConnector()
    {
        return connector;
    }

    public void setConnector(final org.mule.api.transport.Connector connector)
    {
        this.connector = connector;
    }

    public String getProjectId()
    {
        return projectId;
    }

    public void setProjectId(final String projectId)
    {
        this.projectId = projectId;
    }
}
