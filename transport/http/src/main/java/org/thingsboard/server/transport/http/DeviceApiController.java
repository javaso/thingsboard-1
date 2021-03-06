/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
package org.thingsboard.server.transport.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.server.common.data.security.DeviceTokenCredentials;
import org.thingsboard.server.common.msg.core.*;
import org.thingsboard.server.common.msg.session.AdaptorToSessionActorMsg;
import org.thingsboard.server.common.msg.session.BasicAdaptorToSessionActorMsg;
import org.thingsboard.server.common.msg.session.BasicTransportToDeviceSessionActorMsg;
import org.thingsboard.server.common.msg.session.FromDeviceMsg;
import org.thingsboard.server.common.transport.SessionMsgProcessor;
import org.thingsboard.server.common.transport.adaptor.JsonConverter;
import org.thingsboard.server.common.transport.auth.DeviceAuthService;
import org.thingsboard.server.common.transport.quota.QuotaService;
import org.thingsboard.server.common.transport.quota.host.HostRequestsQuotaService;
import org.thingsboard.server.transport.http.session.HttpSessionCtx;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * HTTP是一种可用于IoT应用程序的通用网络协议。HTTP协议是基于TCP的，并使用请求-响应模型。
 * ThingsBoard服务器节点充当支持HTTP和HTTPS协议的HTTP服务器。
 *
 * @author Andrew Shvayka
 */
@RestController
@RequestMapping("/api/v1")
@Slf4j
public class DeviceApiController {

    //缺省条件下http连接超时是60000
    @Value("${http.request_timeout}")
    private long defaultTimeout;

    @Autowired(required = false)
    private SessionMsgProcessor processor;

    @Autowired(required = false)
    private DeviceAuthService authService;

    @Autowired(required = false)
    private HostRequestsQuotaService quotaService;

    /**
     * 从服务器请求属性值
     * --要向ThingsBoard服务器节点请求客户端或共享设备属性，请将GET请求发送到以下URL:
     * http(s)://host:port/api/v1/$ACCESS_TOKEN/attributes?clientKeys=attribute1,attribute2&sharedKeys=shared1,shared2
     *
     * 例如:
     * request:  curl -v -X GET http://localhost:8080/api/v1/$ACCESS_TOKEN/attributes?clientKeys=attribute1,attribute2&sharedKeys=shared1,shared2
     * response: {"key1":"value1"}
     * @param deviceToken
     * @param clientKeys
     * @param sharedKeys
     * @param httpRequest
     * @return
     */
    @RequestMapping(value = "/{deviceToken}/attributes", method = RequestMethod.GET, produces = "application/json")
    /**
     * DeferredResult<ResponseEntity>都是为了异步生成返回值提供基本的支持
     */
    public DeferredResult<ResponseEntity> getDeviceAttributes(@PathVariable("deviceToken") String deviceToken,
                                                              @RequestParam(value = "clientKeys", required = false, defaultValue = "") String clientKeys,
                                                              @RequestParam(value = "sharedKeys", required = false, defaultValue = "") String sharedKeys,
                                                              HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        if (quotaExceeded(httpRequest, responseWriter)) {
            return responseWriter;
        }
        HttpSessionCtx ctx = getHttpSessionCtx(responseWriter);
        if (ctx.login(new DeviceTokenCredentials(deviceToken))) {
            GetAttributesRequest request;
            if (StringUtils.isEmpty(clientKeys) && StringUtils.isEmpty(sharedKeys)) {
                request = new BasicGetAttributesRequest(0);
            } else {
                Set<String> clientKeySet = !StringUtils.isEmpty(clientKeys) ? new HashSet<>(Arrays.asList(clientKeys.split(","))) : null;
                Set<String> sharedKeySet = !StringUtils.isEmpty(sharedKeys) ? new HashSet<>(Arrays.asList(sharedKeys.split(","))) : null;
                request = new BasicGetAttributesRequest(0, clientKeySet, sharedKeySet);
            }
            process(ctx, request);
        } else {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
        }

        return responseWriter;
    }

    /**
     * 要将客户端设备属性发布到ThingsBoard服务器节点
     * @param deviceToken
     * @param json
     * @param request
     * @return
     */
    @RequestMapping(value = "/{deviceToken}/attributes", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postDeviceAttributes(@PathVariable("deviceToken") String deviceToken,
                                                               @RequestBody String json, HttpServletRequest request) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        if (quotaExceeded(request, responseWriter)) {
            return responseWriter;
        }
        HttpSessionCtx ctx = getHttpSessionCtx(responseWriter);
        if (ctx.login(new DeviceTokenCredentials(deviceToken))) {
            try {
                process(ctx, JsonConverter.convertToAttributes(new JsonParser().parse(json)));
            } catch (IllegalStateException | JsonSyntaxException ex) {
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
            }
        } else {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
        }
        return responseWriter;
    }

    /**
     * 要将遥测数据发布到ThingsBoard服务器节点，请将POST请求发送到以下URL:
     *  http(s)://host:port/api/v1/$ACCESS_TOKEN/telemetry
     *  例如： request: {"key1":"value1", "key2":"value2"}
     * @param deviceToken
     * @param json
     * @param request
     * @return
     */
    @RequestMapping(value = "/{deviceToken}/telemetry", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postTelemetry(@PathVariable("deviceToken") String deviceToken,
                                                        @RequestBody String json, HttpServletRequest request) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        if (quotaExceeded(request, responseWriter)) {
            return responseWriter;
        }
        HttpSessionCtx ctx = getHttpSessionCtx(responseWriter);
        if (ctx.login(new DeviceTokenCredentials(deviceToken))) {
            try {
                process(ctx, JsonConverter.convertToTelemetry(new JsonParser().parse(json)));
            } catch (IllegalStateException | JsonSyntaxException ex) {
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
            }
        } else {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
        }
        return responseWriter;
    }

    /**
     * 要从服务器订阅RPC命令，请将带有可选“timeout”请求参数的GET请求发送到以下URL：
     * request: http(s)://host:port/api/v1/$ACCESS_TOKEN/rpc
     * 订阅后，如果没有对特定设备的请求，客户端可能会收到rpc请求或超时消息。RPC请求体的示例如下所示：
     * {
     *   "id": "1",
     *   "method": "setGpio",
     *   "params": {
     *     "pin": "23",
     *     "value": 1
     *   }
     * }
     *
     * id - 请求id，整数请求标识符
     * method - RPC方法名称，字符串
     * params - RPC方法参数，自定义json对象
     *
     *
     * @param deviceToken
     * @param timeout
     * @param request
     * @return
     */
    @RequestMapping(value = "/{deviceToken}/rpc", method = RequestMethod.GET, produces = "application/json")
    public DeferredResult<ResponseEntity> subscribeToCommands(@PathVariable("deviceToken") String deviceToken,
                                                              @RequestParam(value = "timeout", required = false, defaultValue = "0") long timeout,
                                                              HttpServletRequest request) {

        return subscribe(deviceToken, timeout, new RpcSubscribeMsg(), request);
    }

    /**
     * 回复报文
     *  http://host:port/api/v1/$ACCESS_TOKEN/rpc/{$id}
     * @param deviceToken
     * @param requestId
     * @param json
     * @param request
     * @return
     */
    @RequestMapping(value = "/{deviceToken}/rpc/{requestId}", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> replyToCommand(@PathVariable("deviceToken") String deviceToken,
                                                         @PathVariable("requestId") Integer requestId,
                                                         @RequestBody String json, HttpServletRequest request) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        if (quotaExceeded(request, responseWriter)) {
            return responseWriter;
        }
        HttpSessionCtx ctx = getHttpSessionCtx(responseWriter);
        if (ctx.login(new DeviceTokenCredentials(deviceToken))) {
            try {
                JsonObject response = new JsonParser().parse(json).getAsJsonObject();
                process(ctx, new ToDeviceRpcResponseMsg(requestId, response.toString()));
            } catch (IllegalStateException | JsonSyntaxException ex) {
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
            }
        } else {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
        }
        return responseWriter;
    }

    /**
     * 要将RPC命令发送到服务器，请将POST请求发送到以下URL：
     * http://host:port/api/v1/$ACCESS_TOKEN/rpc
     * 请求和响应正文都应该是有效的JSON文档。文档的内容特定于将处理您的请求的规则节点。
     * request: {"method": "getTime", "params":{}}
     * response: {"time":"2016 11 21 12:54:44.287"}
     * @param deviceToken
     * @param json
     * @param httpRequest
     * @return
     */
    @RequestMapping(value = "/{deviceToken}/rpc", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postRpcRequest(@PathVariable("deviceToken") String deviceToken,
                                                         @RequestBody String json, HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        if (quotaExceeded(httpRequest, responseWriter)) {
            return responseWriter;
        }
        HttpSessionCtx ctx = getHttpSessionCtx(responseWriter);
        if (ctx.login(new DeviceTokenCredentials(deviceToken))) {
            try {
                JsonObject request = new JsonParser().parse(json).getAsJsonObject();
                process(ctx, new ToServerRpcRequestMsg(0,
                        request.get("method").getAsString(),
                        request.get("params").toString()));
            } catch (IllegalStateException | JsonSyntaxException ex) {
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
            }
        } else {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
        }
        return responseWriter;
    }

    /**
     * 要订阅共享设备属性更改，请将带有可选“超时”请求参数的GET请求发送到以下URL：
     * 请将带有可选“超时”请求参数的GET请求发送到以下URL：
     * 一旦服务器端组件（REST API或规则链）之一更改共享属性，客户端将收到以下更新：
     * 例如:
     * request: curl -v -X GET http://localhost:8080/api/v1/$ACCESS_TOKEN/attributes/updates?timeout=20000
     * response: {"key1":"value1"}
     * @param deviceToken
     * @param timeout
     * @param httpRequest
     * @return
     */
    @RequestMapping(value = "/{deviceToken}/attributes/updates", method = RequestMethod.GET, produces = "application/json")
    public DeferredResult<ResponseEntity> subscribeToAttributes(@PathVariable("deviceToken") String deviceToken,
                                                                @RequestParam(value = "timeout", required = false, defaultValue = "0") long timeout,
                                                                HttpServletRequest httpRequest) {

        return subscribe(deviceToken, timeout, new AttributesSubscribeMsg(), httpRequest);
    }

    private DeferredResult<ResponseEntity> subscribe(String deviceToken, long timeout, FromDeviceMsg msg, HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        if (quotaExceeded(httpRequest, responseWriter)) {
            return responseWriter;
        }
        HttpSessionCtx ctx = getHttpSessionCtx(responseWriter, timeout);
        if (ctx.login(new DeviceTokenCredentials(deviceToken))) {
            try {
                process(ctx, msg);
            } catch (IllegalStateException | JsonSyntaxException ex) {
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
            }
        } else {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
        }
        return responseWriter;
    }

    private HttpSessionCtx getHttpSessionCtx(DeferredResult<ResponseEntity> responseWriter) {
        return getHttpSessionCtx(responseWriter, defaultTimeout);
    }

    private HttpSessionCtx getHttpSessionCtx(DeferredResult<ResponseEntity> responseWriter, long timeout) {
        return new HttpSessionCtx(processor, authService, responseWriter, timeout != 0 ? timeout : defaultTimeout);
    }

    private void process(HttpSessionCtx ctx, FromDeviceMsg request) {
        AdaptorToSessionActorMsg msg = new BasicAdaptorToSessionActorMsg(ctx, request);
        processor.process(new BasicTransportToDeviceSessionActorMsg(ctx.getDevice(), msg));
    }

    private boolean quotaExceeded(HttpServletRequest request, DeferredResult<ResponseEntity> responseWriter) {
        /**
         * 查找请求设备IP是在指标或者是已配的客户，租户下
         */
        if (quotaService.isQuotaExceeded(request.getRemoteAddr())) {
            /**
             * 打印REST 超出指标 断开连接，请求IP
             */
            log.warn("REST Quota exceeded for [{}] . Disconnect", request.getRemoteAddr());
            /**
             * 返回HTTP状态为:509
             */
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.BANDWIDTH_LIMIT_EXCEEDED));
            return true;
        }
        return false;
    }

}
