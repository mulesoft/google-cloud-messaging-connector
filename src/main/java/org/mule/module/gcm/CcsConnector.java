/**
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 **/

package org.mule.module.gcm;

import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.codehaus.jackson.type.TypeReference;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.PacketExtension;
import org.jivesoftware.smack.provider.PacketExtensionProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.mule.api.ConnectionException;
import org.mule.api.ConnectionExceptionCode;
import org.mule.api.MuleException;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Connect;
import org.mule.api.annotations.ConnectionIdentifier;
import org.mule.api.annotations.Connector;
import org.mule.api.annotations.Disconnect;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.Source;
import org.mule.api.annotations.ValidateConnection;
import org.mule.api.annotations.param.ConnectionKey;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.Optional;
import org.mule.api.callback.SourceCallback;
import org.mule.module.gcm.model.CcsMessageBase.MessageType;
import org.mule.module.gcm.model.CcsRequest;
import org.mule.module.gcm.model.CcsResponse;
import org.mule.module.gcm.model.Data;
import org.mule.util.MapUtils;
import org.mule.util.StringUtils;
import org.xmlpull.v1.XmlPullParser;

/**
 * Mule connector for Google Cloud Messaging (HTTP API).
 * <p/>
 * {@sample.xml ../../../doc/ccs-connector.xml.sample ccs:config}
 * 
 * @author MuleSoft, Inc.
 */
@Connector(name = "ccs", schemaVersion = "1.0", friendlyName = "CCS (XMPP)", minMuleVersion = "3.4", description = "Cloud Connection Server Connector")
public class CcsConnector extends AbstractGcmConnector
{
    private static final String GCM_EXTENSION_NAMESPACE = "google:mobile:data";
    private static final String GCM_EXTENSION_ELEMENT_NAME = "gcm";

    private static class GcmPacketExtension implements PacketExtension
    {
        private final String json;
        private final String xml;

        GcmPacketExtension(final String json)
        {
            this.json = json;

            final StringBuilder xmlBuilder = new StringBuilder("<").append(getElementName())
                .append(" xmlns='")
                .append(getNamespace())
                .append("'>")
                .append(StringEscapeUtils.escapeXml(json))
                .append("</")
                .append(getElementName())
                .append(">");

            xml = xmlBuilder.toString();
        }

        String getJson()
        {
            return json;
        }

        @Override
        public String getElementName()
        {
            return GCM_EXTENSION_ELEMENT_NAME;
        }

        @Override
        public String getNamespace()
        {
            return GCM_EXTENSION_NAMESPACE;
        }

        @Override
        public String toXML()
        {
            return xml;
        }
    }

    private static class GcmPacketExtensionProvider implements PacketExtensionProvider
    {
        @Override
        public PacketExtension parseExtension(final XmlPullParser parser) throws Exception
        {
            final int tagType = parser.next();
            if (tagType != XmlPullParser.TEXT)
            {
                throw new IllegalStateException("Unexpected tag type: " + XmlPullParser.TYPES[tagType]);
            }
            return new GcmPacketExtension(parser.getText());
        }
    }

    private static final TypeReference<CcsResponse> CCS_RESPONSE_TYPE_REFERENCE = new TypeReference<CcsResponse>()
    {
        // NOOP
    };

    /**
     * If set to true, ACK and NACK messages are delivered over the message source, otherwise they
     * are silently dropped.
     */
    @Configurable
    @Optional
    @Default("false")
    private boolean deliverAckNackMessages;

    /**
     * If set to true, the connector will automatically acknowledge messages. If set to false,
     * messages will have to be acknowledged manually.
     */
    @Configurable
    @Optional
    @Default("false")
    private boolean autoAckMessages;

    private String projectId;
    private XMPPConnection xmppConnection;

    /**
     * Connect and authenticate to the CSS server.
     * 
     * @param projectId The project ID (aka sender ID).
     * @throws ConnectionException throw in case connecting to the CCS server fails.
     */
    @Connect
    public void connect(@ConnectionKey final String projectId) throws ConnectionException
    {
        this.projectId = projectId;

        ProviderManager.getInstance().addExtensionProvider(GCM_EXTENSION_ELEMENT_NAME,
            GCM_EXTENSION_NAMESPACE, new GcmPacketExtensionProvider());

        try
        {
            final ConnectionConfiguration config = new ConnectionConfiguration("gcm.googleapis.com", 5235);
            config.setSecurityMode(SecurityMode.enabled);
            config.setReconnectionAllowed(true);
            config.setRosterLoadedAtLogin(false);
            config.setSendPresence(false);
            config.setSocketFactory(SSLSocketFactory.getDefault());

            xmppConnection = new XMPPConnection(config);
            xmppConnection.connect();

            if (!xmppConnection.isConnected())
            {
                throw new ConnectionException(ConnectionExceptionCode.CANNOT_REACH, null,
                    "XMPP connection failed to: "
                                    + ToStringBuilder.reflectionToString(config,
                                        ToStringStyle.SHORT_PREFIX_STYLE));
            }
        }
        catch (final XMPPException xmppe)
        {
            throw new ConnectionException(ConnectionExceptionCode.CANNOT_REACH, null, xmppe.getMessage(),
                xmppe);
        }

        try
        {
            xmppConnection.login(getLoginUser(), getApiKey());
        }
        catch (final XMPPException e)
        {
            throw new ConnectionException(ConnectionExceptionCode.INCORRECT_CREDENTIALS, null,
                e.getMessage(), e);
        }
    }

    @Disconnect
    public void disconnect()
    {
        if (xmppConnection != null)
        {
            xmppConnection.disconnect();
        }

        xmppConnection = null;
    }

    @ConnectionIdentifier
    public String getLoginUser()
    {
        return projectId + "@gcm.googleapis.com";
    }

    @ValidateConnection
    public boolean isConnected()
    {
        return xmppConnection != null && xmppConnection.isConnected();
    }

    /**
     * Dispatch a message to a device.
     * <p/>
     * {@sample.xml ../../../doc/ccs-connector.xml.sample ccs:dispatch-message-no-data}
     * <p/>
     * {@sample.xml ../../../doc/ccs-connector.xml.sample ccs:dispatch-message-with-data}
     * 
     * @param registrationId the device registration ID.
     * @param messageId the unique ID of the message. If left blank, one will be generated with
     *            {@link UUID#randomUUID()}.
     * @param data the key-value pairs of the message's payload data.
     * @param delayWhileIdle indicates that the message should not be sent immediately if the device
     *            is idle.
     * @param timeToLiveSeconds how long (in seconds) the message should be kept on GCM storage if
     *            the device is offline.
     * @throws Exception thrown in case anything goes wrong with the operation.
     */
    @Processor
    public void dispatchMessage(final String registrationId,
                                @Optional final String messageId,
                                @Optional final Map<String, Object> data,
                                @Optional @Default("false") final boolean delayWhileIdle,
                                @Optional @Default("2419200") final int timeToLiveSeconds) throws Exception
    {
        final CcsRequest ccsRequest = new CcsRequest();
        ccsRequest.setTo(registrationId);
        ccsRequest.setDelayWhileIdle(delayWhileIdle);
        ccsRequest.setTimeToLive(timeToLiveSeconds);

        if (StringUtils.isBlank(messageId))
        {
            ccsRequest.setMessageId(UUID.randomUUID().toString());
        }
        else
        {
            ccsRequest.setMessageId(messageId);
        }

        if (MapUtils.isEmpty(data))
        {
            final Data ccsData = new Data();
            ccsData.getAdditionalProperties().putAll(data);
            ccsRequest.setData(ccsData);
        }

        xmppSend(ccsRequest);
    }

    /**
     * Manually acknowledge a message.
     * <p/>
     * {@sample.xml ../../../doc/ccs-connector.xml.sample ccs:acknowledge-message}
     * 
     * @param registrationId the device registration ID.
     * @param messageId the unique ID of the message.
     * @throws Exception thrown in case anything goes wrong with the operation.
     */
    @Processor
    public void acknowledgeMessage(final String registrationId, final String messageId) throws Exception
    {
        final CcsRequest ccsRequest = new CcsRequest();
        ccsRequest.setTo(registrationId);
        ccsRequest.setMessageId(messageId);

        xmppSend(ccsRequest);
    }

    /**
     * Source that receives inbound messages.
     * <p/>
     * {@sample.xml ../../../doc/ccs-connector.xml.sample ccs:receive}
     * 
     * @param sourceCallback the {@link SourceCallback} called to deliver the message.
     */
    @Source
    public void receive(final SourceCallback sourceCallback)
    {
        xmppConnection.addPacketListener(new PacketListener()
        {
            @Override
            public void processPacket(final Packet packet)
            {
                try
                {
                    handleInboundMessage(sourceCallback, (Message) packet);
                }
                catch (final Exception e)
                {
                    logger.error("Failed to handle inbound message: " + packet, e);
                }

            }
        }, new PacketFilter()
        {
            @Override
            public boolean accept(final Packet packet)
            {
                return packet instanceof Message;
            }
        });
    }

    private void xmppSend(final CcsRequest ccsRequest) throws MuleException, XMPPException
    {
        final String jsonRequest = serializeEntityToJson(ccsRequest);
        final Message xmppMessage = new Message();
        xmppMessage.addExtension(new GcmPacketExtension(jsonRequest));
        xmppConnection.sendPacket(xmppMessage);
    }

    private void handleInboundMessage(final SourceCallback sourceCallback, final Message message)
        throws Exception
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Received message w/packet ID: " + message.getPacketID());
        }

        final GcmPacketExtension gcmPacketExtension = (GcmPacketExtension) message.getExtension(
            GCM_EXTENSION_ELEMENT_NAME, GCM_EXTENSION_NAMESPACE);

        if (gcmPacketExtension == null)
        {
            logger.warn("Dropping unsupported message: " + message.toXML());
        }

        final CcsResponse ccsResponse = deserializeJsonToEntity(CCS_RESPONSE_TYPE_REFERENCE,
            gcmPacketExtension.getJson());

        if (isAckOrNackMessage(ccsResponse))
        {
            if (!isDeliverAckNackMessages())
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Dropping ack/nack message: " + ccsResponse);
                }
                return;
            }
        }
        else if (isAutoAckMessages())
        {
            acknowledgeMessage(ccsResponse.getFrom(), ccsResponse.getMessageId());
        }

        sourceCallback.process(ccsResponse);
    }

    private boolean isAckOrNackMessage(final CcsResponse ccsResponse)
    {
        return ccsResponse.getMessageType() == MessageType.ACK
               || ccsResponse.getMessageType() == MessageType.NACK;
    }

    public boolean isDeliverAckNackMessages()
    {
        return deliverAckNackMessages;
    }

    public void setDeliverAckNackMessages(final boolean deliverAckNackMessages)
    {
        this.deliverAckNackMessages = deliverAckNackMessages;
    }

    public boolean isAutoAckMessages()
    {
        return autoAckMessages;
    }

    public void setAutoAckMessages(final boolean autoAckMessages)
    {
        this.autoAckMessages = autoAckMessages;
    }
}
