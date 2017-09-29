/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 * Modifications Copyright (c) 2017 RxNetty Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.reactivex.netty.http.server;

import java.io.File;
import java.util.Map;
import java.util.Set;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.AsciiString;
import io.reactivex.Flowable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.internal.functions.ObjectHelper;
import io.reactivex.netty.http.websocket.WebsocketInbound;
import io.reactivex.netty.http.websocket.WebsocketOutbound;
import org.reactivestreams.Publisher;
import io.reactivex.netty.FutureFlowable;
import io.reactivex.netty.NettyContext;
import io.reactivex.netty.NettyOutbound;
import io.reactivex.netty.channel.ContextHandler;
import io.reactivex.netty.http.Cookies;
import io.reactivex.netty.http.HttpOperations;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;

/**
 * Conversion between Netty types and RxJava types ({@link HttpOperations}.
 *
 * @author Stephane Maldini
 */
class HttpServerOperations extends HttpOperations<HttpServerRequest, HttpServerResponse>
		implements HttpServerRequest, HttpServerResponse {

	@SuppressWarnings("unchecked")
	static HttpServerOperations bindHttp(Channel channel,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			ContextHandler<?> context,
			Object msg) {
		return new HttpServerOperations(channel, handler, context, (HttpRequest) msg);
	}

	final HttpResponse nettyResponse;
	final HttpHeaders  responseHeaders;
	final Cookies     cookieHolder;
	final HttpRequest nettyRequest;

	Function<? super String, Map<String, String>> paramsResolver;

	HttpServerOperations(Channel ch, HttpServerOperations replaced) {
		super(ch, replaced);
		this.cookieHolder = replaced.cookieHolder;
		this.responseHeaders = replaced.responseHeaders;
		this.nettyResponse = replaced.nettyResponse;
		this.paramsResolver = replaced.paramsResolver;
		this.nettyRequest = replaced.nettyRequest;
	}

	HttpServerOperations(Channel ch,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			ContextHandler<?> context,
			HttpRequest nettyRequest) {
		super(ch, handler, context);
		this.nettyRequest = ObjectHelper.requireNonNull(nettyRequest, "nettyRequest");
		this.nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.responseHeaders = nettyResponse.headers();
		this.cookieHolder = Cookies.newServerRequestHolder(requestHeaders());
		chunkedTransfer(true);


	}

	@Override
	public HttpServerOperations context(Consumer<NettyContext> contextCallback) {
		try {
			contextCallback.accept(context());
		} catch (Exception e) {
			throw Exceptions.propagate(e);
		}
		return this;
	}

	@Override
	protected HttpMessage newFullEmptyBodyMessage() {
		HttpResponse res =
				new DefaultFullHttpResponse(version(), status(), EMPTY_BUFFER);

		res.headers()
		   .set(responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING)
		                       .setInt(HttpHeaderNames.CONTENT_LENGTH, 0));
		return res;
	}

	@Override
	public HttpServerResponse addCookie(Cookie cookie) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(HttpHeaderNames.SET_COOKIE,
					ServerCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	public HttpServerResponse chunkedTransfer(boolean chunked) {
		if (!hasSentHeaders() && HttpUtil.isTransferEncodingChunked(nettyResponse) != chunked) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
		}

		markPersistent(chunked);
		return this;
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		if (cookieHolder != null) {
			return cookieHolder.getCachedCookies();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpServerResponse header(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse headers(HttpHeaders headers) {
		if (!hasSentHeaders()) {
			this.responseHeaders.set(headers);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		return requestHeaders().contains(HttpHeaderNames.UPGRADE,
				HttpHeaderValues.WEBSOCKET,
				true);
	}

	@Override
	public HttpServerResponse keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyResponse, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	@Override
	public String param(CharSequence key) {
		ObjectHelper.requireNonNull(key, "key");
		Map<String, String> params = null;
		if (paramsResolver != null) {
			try {
				params = this.paramsResolver.apply(uri());
			} catch (Exception e) {
				throw Exceptions.propagate(e);
			}
		}
		return null != params ? params.get(key) : null;
	}

	@Override
	public Map<String, String> params() {
		try {
			return null != paramsResolver ? paramsResolver.apply(uri()) : null;
		} catch (Exception e) {
			throw Exceptions.propagate(e);
		}
	}

	@Override
	public HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> headerResolver) {
		this.paramsResolver = headerResolver;
		return this;
	}

	@Override
	public Flowable<?> receiveObject() {
		// Handle the 'Expect: 100-continue' header if necessary.
		// TODO: Respond with 413 Request Entity Too Large
		//   and discard the traffic or close the connection.
		//       No need to notify the upstream handlers - just log.
		//       If decoding a response, just throw an error.
		if (HttpUtil.is100ContinueExpected(nettyRequest)) {
			return FutureFlowable.deferFuture(() -> channel().writeAndFlush(CONTINUE))
			                 .ignoreElements()
			                 .andThen(super.receiveObject());
		}
		else {
			return super.receiveObject();
		}
	}

	@Override
	public HttpHeaders requestHeaders() {
		if (nettyRequest != null) {
			return nettyRequest.headers();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpHeaders responseHeaders() {
		return responseHeaders;
	}

	@Override
	public Flowable<Void> send() {
		if (markSentHeaderAndBody()) {
			HttpMessage response = newFullEmptyBodyMessage();
			return FutureFlowable.deferFuture(() -> channel().writeAndFlush(response));
		}
		else {
			return Flowable.empty();
		}
	}

	@Override
	public NettyOutbound sendFile(File file) {
		try {
			return sendFile(file, 0L, file.length());
		}
		catch (Throwable t) {
			return then(sendNotFound());
		}
	}

	@Override
	public Flowable<Void> sendNotFound() {
		return this.status(HttpResponseStatus.NOT_FOUND)
		           .send();
	}

	@Override
	public Flowable<Void> sendRedirect(String location) {
		ObjectHelper.requireNonNull(location, "location");
		return this.status(HttpResponseStatus.FOUND)
		           .header(HttpHeaderNames.LOCATION, location)
		           .send();
	}

	/**
	 * @return the Transfer setting SSE for this http connection (e.g. event-stream)
	 */
	@Override
	public HttpServerResponse sse() {
		header(HttpHeaderNames.CONTENT_TYPE, EVENT_STREAM);
		return this;
	}

	@Override
	public HttpResponseStatus status() {
		return HttpResponseStatus.valueOf(this.nettyResponse.status()
		                                                    .code());
	}

	@Override
	public HttpServerResponse status(HttpResponseStatus status) {
		if (!hasSentHeaders()) {
			this.nettyResponse.setStatus(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Flowable<Void> sendWebsocket(String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		return withWebsocketSupport(uri(), protocols, websocketHandler);
	}

	@Override
	public String uri() {
		if (nettyRequest != null) {
			return nettyRequest.uri();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpVersion version() {
		if (nettyRequest != null) {
			return nettyRequest.protocolVersion();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	protected void onHandlerStart() {
		applyHandler();
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpContent) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			if (msg instanceof LastHttpContent) {
				onInboundComplete();
				if (isOutboundDone()) {
					onHandlerTerminate();
				}
				else {
					//force auto read to enable more accurate close selection now inbound is done
					channel().config()
					         .setAutoRead(true);
				}
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onOutboundComplete() {
		if (isWebsocket()) {
			return;
		}

		final ChannelFuture f;
		if (markSentHeaderAndBody()) {
			f = channel().writeAndFlush(newFullEmptyBodyMessage());
		}
		else if (markSentBody()) {
			f = channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		}
		else{
			onHandlerTerminate();
			return;
		}
		f.addListener(s -> {
			if (isOutboundDone()) {
				onHandlerTerminate();
			}
		});
	}

	@Override
	protected void onOutboundError(Throwable err) {

		if (!channel().isActive()) {
			super.onOutboundError(err);
			return;
		}

		discreteRemoteClose(err);
		if (markSentHeaders()) {
			HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.INTERNAL_SERVER_ERROR);
			response.headers()
			        .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
			        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
			channel().writeAndFlush(response)
			         .addListener(ChannelFutureListener.CLOSE);
			return;
		}

		if (markSentBody()) {
			channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
			         .addListener(ChannelFutureListener.CLOSE);
			return;
		}
		channel().writeAndFlush(EMPTY_BUFFER)
		         .addListener(ChannelFutureListener.CLOSE);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyResponse;
	}

	final Flowable<Void> withWebsocketSupport(String url,
			String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		ObjectHelper.requireNonNull(websocketHandler, "websocketHandler");
		if (markSentHeaders()) {
			HttpServerWSOperations ops = new HttpServerWSOperations(url, protocols, this);

			if (replace(ops)) {
				return FutureFlowable.from(ops.handshakerResult)
				                 .ignoreElements()
				                 .andThen(Flowable.defer(() -> websocketHandler.apply(ops, ops)))
				                 .doOnError(ops)
				                 .doOnComplete(() -> ops.accept(null));
			}
		}
		return Flowable.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	final static AsciiString      EVENT_STREAM = new AsciiString("text/event-stream");
	final static FullHttpResponse CONTINUE     =
			new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.CONTINUE,
					EMPTY_BUFFER);

	@Override
	protected void handleOutboundWithNoContent() {
		int status = nettyResponse.status().code();
		if (status == 204 || status == 205 || status == 304) {
			nettyResponse.headers()
			             .remove(HttpHeaderNames.TRANSFER_ENCODING)
			             .set(HttpHeaderNames.CONTENT_LENGTH, 0);
		}
	}
}
