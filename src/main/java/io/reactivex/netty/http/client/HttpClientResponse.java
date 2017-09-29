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

package io.reactivex.netty.http.client;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.Flowable;
import io.reactivex.functions.Action;
import io.reactivex.functions.BiFunction;
import io.reactivex.netty.NettyContext;
import io.reactivex.netty.NettyInbound;
import io.reactivex.netty.http.HttpInfos;
import io.reactivex.netty.http.websocket.WebsocketInbound;
import io.reactivex.netty.http.websocket.WebsocketOutbound;
import org.reactivestreams.Publisher;

/**
 * An HttpClient Reactive read contract for incoming response. It inherits several
 * accessor
 * related to HTTP
 * flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpClientResponse extends NettyInbound, HttpInfos, NettyContext {

	@Override
	default HttpClientResponse addHandlerFirst(ChannelHandler handler) {
		NettyContext.super.addHandlerFirst(handler);
		return this;
	}

	@Override
	HttpClientResponse addHandlerFirst(String name, ChannelHandler handler);

	@Override
	default HttpClientResponse addHandlerLast(ChannelHandler handler) {
		return addHandlerLast(handler.getClass().getSimpleName(), handler);
	}

	@Override
	HttpClientResponse addHandlerLast(String name, ChannelHandler handler);

	@Override
	default HttpClientResponse addHandler(ChannelHandler handler) {
		return addHandler(handler.getClass().getSimpleName(), handler);
	}

	@Override
	HttpClientResponse addHandler(String name, ChannelHandler handler);

	@Override
	HttpClientResponse removeHandler(String name);

	@Override
	HttpClientResponse replaceHandler(String name, ChannelHandler handler);

	@Override
	HttpClientResponse onClose(Action onClose);

	@Override
	default HttpClientResponse onReadIdle(long idleTimeout, Runnable onReadIdle) {
		NettyInbound.super.onReadIdle(idleTimeout, onReadIdle);
		return this;
	}

	/**
	 * Return a {@link Flowable} of {@link HttpContent} containing received chunks
	 *
	 * @return a {@link Flowable} of {@link HttpContent} containing received chunks
	 */
	default Flowable<HttpContent> receiveContent(){
		return receiveObject().ofType(HttpContent.class);
	}

	/**
	 * Unidirectional conversion to a {@link WebsocketInbound}.
	 * receive operations are invoked on handshake success, otherwise connection wasn't
	 * upgraded by the server and the returned {@link WebsocketInbound} fails.
	 *
	 * @return a {@link WebsocketInbound} completing when upgrade is confirmed
	 */
	WebsocketInbound receiveWebsocket();

	/**
	 * Duplex conversion to {@link WebsocketInbound}, {@link WebsocketOutbound} and a
	 * closing {@link Publisher}. Flowable and Callback are invoked on handshake success,
	 * otherwise the returned {@link Flowable} fails.
	 *
	 * @param websocketHandler the in/out handler for ws transport
	 *
	 * @return a {@link Flowable} completing when upgrade is confirmed
	 */
	default Flowable<Void> receiveWebsocket(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		return receiveWebsocket(null, websocketHandler);
	}

	/**
	 * Duplex conversion to {@link WebsocketInbound}, {@link WebsocketOutbound} and a
	 * closing {@link Publisher}. Flowable and Callback are invoked on handshake success,
	 * otherwise the returned {@link Flowable} fails.
	 *
	 * @param protocols optional sub-protocol
	 * @param websocketHandler the in/out handler for ws transport
	 *
	 * @return a {@link Flowable} completing when upgrade is confirmed
	 */
	Flowable<Void> receiveWebsocket(String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler);

	/**
	 * Return the previous redirections or empty array
	 *
	 * @return the previous redirections or empty array
	 */
	String[] redirectedFrom();

	/**
	 * Return response HTTP headers.
	 *
	 * @return response HTTP headers.
	 */
	HttpHeaders responseHeaders();

	/**
	 * @return the resolved HTTP Response Status
	 */
	HttpResponseStatus status();
}
