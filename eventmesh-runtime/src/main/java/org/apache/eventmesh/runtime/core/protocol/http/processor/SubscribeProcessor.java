/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.core.protocol.http.processor;

import org.apache.eventmesh.common.protocol.SubscriptionItem;
import org.apache.eventmesh.common.protocol.http.HttpCommand;
import org.apache.eventmesh.common.protocol.http.body.client.SubscribeRequestBody;
import org.apache.eventmesh.common.protocol.http.body.client.SubscribeResponseBody;
import org.apache.eventmesh.common.protocol.http.body.message.SendMessageResponseBody;
import org.apache.eventmesh.common.protocol.http.common.EventMeshRetCode;
import org.apache.eventmesh.common.protocol.http.common.RequestCode;
import org.apache.eventmesh.common.protocol.http.header.client.SubscribeRequestHeader;
import org.apache.eventmesh.common.protocol.http.header.client.SubscribeResponseHeader;
import org.apache.eventmesh.common.utils.IPUtils;
import org.apache.eventmesh.common.utils.JsonUtils;
import org.apache.eventmesh.runtime.acl.Acl;
import org.apache.eventmesh.runtime.boot.EventMeshHTTPServer;
import org.apache.eventmesh.runtime.constants.EventMeshConstants;
import org.apache.eventmesh.runtime.core.consumer.ClientInfo;
import org.apache.eventmesh.runtime.core.consumer.SubscriptionManager;
import org.apache.eventmesh.runtime.core.protocol.http.async.AsyncContext;
import org.apache.eventmesh.runtime.core.protocol.http.async.CompleteHandler;
import org.apache.eventmesh.runtime.core.protocol.http.processor.inf.HttpRequestProcessor;
import org.apache.eventmesh.runtime.util.EventMeshUtil;
import org.apache.eventmesh.runtime.util.RemotingHelper;
import org.apache.eventmesh.runtime.util.WebhookUtil;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;

public class SubscribeProcessor implements HttpRequestProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscribeProcessor.class);

    private final transient EventMeshHTTPServer eventMeshHTTPServer;

    public SubscribeProcessor(final EventMeshHTTPServer eventMeshHTTPServer) {
        this.eventMeshHTTPServer = eventMeshHTTPServer;
    }

    @Override
    public void processRequest(final ChannelHandlerContext ctx, final AsyncContext<HttpCommand> asyncContext)
            throws Exception {
        HttpCommand responseEventMeshCommand;
        final HttpCommand request = asyncContext.getRequest();
        final Integer requestCode = Integer.valueOf(asyncContext.getRequest().getRequestCode());
        final String localAddress = IPUtils.getLocalAddress();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("cmd={}|{}|client2eventMesh|from={}|to={}",
                    RequestCode.get(requestCode),
                    EventMeshConstants.PROTOCOL_HTTP,
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()), localAddress);
        }

        final SubscribeRequestHeader subscribeRequestHeader = (SubscribeRequestHeader) request.getHeader();
        final SubscribeRequestBody subscribeRequestBody = (SubscribeRequestBody) request.getBody();
        final SubscribeResponseHeader subscribeResponseHeader =
                SubscribeResponseHeader
                        .buildHeader(requestCode,
                                eventMeshHTTPServer.getEventMeshHttpConfiguration().getEventMeshCluster(),
                                localAddress,
                                eventMeshHTTPServer.getEventMeshHttpConfiguration().getEventMeshEnv(),
                                eventMeshHTTPServer.getEventMeshHttpConfiguration().getEventMeshIDC());

        //validate header
        if (StringUtils.isBlank(subscribeRequestHeader.getIdc())
                || StringUtils.isBlank(subscribeRequestHeader.getPid())
                || !StringUtils.isNumeric(subscribeRequestHeader.getPid())
                || StringUtils.isBlank(subscribeRequestHeader.getSys())) {
            responseEventMeshCommand = request.createHttpCommandResponse(
                    subscribeResponseHeader,
                    SubscribeResponseBody
                            .buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR.getRetCode(),
                                    EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR.getErrMsg()));
            asyncContext.onComplete(responseEventMeshCommand);
            return;
        }

        //validate body
        if (StringUtils.isBlank(subscribeRequestBody.getUrl())
                || CollectionUtils.isEmpty(subscribeRequestBody.getTopics())
                || StringUtils.isBlank(subscribeRequestBody.getConsumerGroup())) {

            responseEventMeshCommand = request.createHttpCommandResponse(
                    subscribeResponseHeader,
                    SubscribeResponseBody
                            .buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getRetCode(),
                                    EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getErrMsg()));
            asyncContext.onComplete(responseEventMeshCommand);
            return;
        }
        final List<SubscriptionItem> subTopicList = subscribeRequestBody.getTopics();

        //do acl check
        if (eventMeshHTTPServer.getEventMeshHttpConfiguration().isEventMeshServerSecurityEnable()) {
            for (final SubscriptionItem item : subTopicList) {
                try {
                    Acl.doAclCheckInHttpReceive(RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                            subscribeRequestHeader.getUsername(),
                            subscribeRequestHeader.getPasswd(),
                            subscribeRequestHeader.getSys(), item.getTopic(),
                            requestCode);
                } catch (Exception e) {

                    responseEventMeshCommand = asyncContext.getRequest().createHttpCommandResponse(
                            subscribeResponseHeader,
                            SendMessageResponseBody
                                    .buildBody(EventMeshRetCode.EVENTMESH_ACL_ERR.getRetCode(),
                                            e.getMessage()));
                    asyncContext.onComplete(responseEventMeshCommand);

                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("CLIENT HAS NO PERMISSION,SubscribeProcessor subscribe failed", e);
                    }
                    return;
                }
            }
        }

        final String url = subscribeRequestBody.getUrl();
        final String consumerGroup = subscribeRequestBody.getConsumerGroup();

        // validate URL
        try {
            if (!IPUtils.isValidDomainOrIp(url, eventMeshHTTPServer.getEventMeshHttpConfiguration().eventMeshIpv4BlackList,
                    eventMeshHTTPServer.getEventMeshHttpConfiguration().eventMeshIpv6BlackList)) {
                LOGGER.error("subscriber url {} is not valid", url);
                responseEventMeshCommand = request.createHttpCommandResponse(
                        subscribeResponseHeader,
                        SubscribeResponseBody
                                .buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getRetCode(),
                                        EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getErrMsg() + " invalid URL: " + url));
                asyncContext.onComplete(responseEventMeshCommand);
                return;
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("subscriber url:{} is invalid.", url, e);
            }
            responseEventMeshCommand = request.createHttpCommandResponse(
                    subscribeResponseHeader,
                    SubscribeResponseBody
                            .buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getRetCode(),
                                    EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getErrMsg() + " invalid URL: " + url));
            asyncContext.onComplete(responseEventMeshCommand);
            return;
        }

        // obtain webhook delivery agreement for Abuse Protection
        if (!WebhookUtil.obtainDeliveryAgreement(eventMeshHTTPServer.httpClientPool.getClient(),
                url, eventMeshHTTPServer.getEventMeshHttpConfiguration().getEventMeshWebhookOrigin())) {
            LOGGER.error("subscriber url {} is not allowed by the target system", url);
            responseEventMeshCommand = request.createHttpCommandResponse(
                    subscribeResponseHeader,
                    SubscribeResponseBody
                            .buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getRetCode(),
                                    EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getErrMsg() + " unauthorized webhook URL: " + url));
            asyncContext.onComplete(responseEventMeshCommand);
            return;
        }

        synchronized (eventMeshHTTPServer.getSubscriptionManager().getLocalClientInfoMapping()) {
            ClientInfo clientInfo = getClientInfo(subscribeRequestHeader);
            SubscriptionManager subscriptionManager = eventMeshHTTPServer.getSubscriptionManager();
            subscriptionManager.registerClient(clientInfo, consumerGroup, subTopicList, url);
            subscriptionManager.updateSubscription(clientInfo, consumerGroup, url, subTopicList);

            final long startTime = System.currentTimeMillis();
            try {
                // subscription relationship change notification
                eventMeshHTTPServer.getConsumerManager().notifyConsumerManager(consumerGroup,
                        eventMeshHTTPServer.getSubscriptionManager().getLocalConsumerGroupMapping().get(consumerGroup));

                final CompleteHandler<HttpCommand> handler = new CompleteHandler<HttpCommand>() {
                    @Override
                    public void onResponse(final HttpCommand httpCommand) {
                        try {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("{}", httpCommand);
                            }
                            eventMeshHTTPServer.sendResponse(ctx, httpCommand.httpResponse());

                            eventMeshHTTPServer.getMetrics().getSummaryMetrics().recordHTTPReqResTimeCost(
                                    System.currentTimeMillis() - request.getReqTime());
                        } catch (Exception ex) {
                            LOGGER.error("onResponse error", ex);
                        }
                    }
                };

                responseEventMeshCommand = request.createHttpCommandResponse(EventMeshRetCode.SUCCESS);
                asyncContext.onComplete(responseEventMeshCommand, handler);
            } catch (Exception e) {
                final HttpCommand err = asyncContext.getRequest().createHttpCommandResponse(
                        subscribeResponseHeader,
                        SubscribeResponseBody
                                .buildBody(EventMeshRetCode.EVENTMESH_SUBSCRIBE_ERR.getRetCode(),
                                        EventMeshRetCode.EVENTMESH_SUBSCRIBE_ERR.getErrMsg()
                                                + EventMeshUtil.stackTrace(e, 2)));
                asyncContext.onComplete(err);

                final long endTime = System.currentTimeMillis();
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error(
                            "message|eventMesh2mq|REQ|ASYNC|send2MQCost={}ms|topic={}"
                                    + "|bizSeqNo={}|uniqueId={}", endTime - startTime,
                            JsonUtils.serialize(subscribeRequestBody.getTopics()),
                            subscribeRequestBody.getUrl(), e);
                }
                eventMeshHTTPServer.getMetrics().getSummaryMetrics().recordSendMsgFailed();
                eventMeshHTTPServer.getMetrics().getSummaryMetrics().recordSendMsgCost(endTime - startTime);
            }
        }
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

    private ClientInfo getClientInfo(final SubscribeRequestHeader subscribeRequestHeader) {
        ClientInfo clientInfo = new ClientInfo();
        clientInfo.setEnv(subscribeRequestHeader.getEnv());
        clientInfo.setIdc(subscribeRequestHeader.getIdc());
        clientInfo.setSys(subscribeRequestHeader.getSys());
        clientInfo.setIp(subscribeRequestHeader.getIp());
        clientInfo.setPid(subscribeRequestHeader.getPid());
        return clientInfo;
    }
}
