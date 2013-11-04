/*
 * (C) Copyright 2013 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package com.kurento.kmf.content.internal.base;

import java.util.concurrent.Future;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;

import com.kurento.kmf.common.exception.Assert;
import com.kurento.kmf.common.exception.KurentoMediaFrameworkException;
import com.kurento.kmf.common.exception.internal.ExceptionUtils;
import com.kurento.kmf.common.exception.internal.ServletUtils;
import com.kurento.kmf.content.ContentHandler;
import com.kurento.kmf.content.ContentSession;
import com.kurento.kmf.content.internal.ContentSessionManager;
import com.kurento.kmf.content.internal.StreamingProxy;
import com.kurento.kmf.content.internal.StreamingProxyListener;
import com.kurento.kmf.content.jsonrpc.JsonRpcResponse;
import com.kurento.kmf.media.HttpEndPoint;
import com.kurento.kmf.media.MediaElement;
import com.kurento.kmf.media.PlayerEndPoint;
import com.kurento.kmf.media.RecorderEndPoint;
import com.kurento.kmf.media.UriEndPoint;
import com.kurento.kmf.media.events.MediaError;
import com.kurento.kmf.media.events.MediaErrorListener;
import com.kurento.kmf.media.events.MediaEventListener;
import com.kurento.kmf.media.events.MediaSessionStartedEvent;
import com.kurento.kmf.media.events.MediaSessionTerminatedEvent;

/**
 * 
 * Abstract definition for HTTP based content request.
 * 
 * @author Luis López (llopez@gsyc.es)
 * @version 1.0.0
 */
public abstract class AbstractHttpBasedContentSession extends
		AbstractContentSession {

	@Autowired
	private StreamingProxy proxy;

	protected boolean useControlProtocol;

	protected boolean redirect;

	protected volatile Future<?> tunnellingProxyFuture;

	private UriEndPoint uriEndPoint;

	public AbstractHttpBasedContentSession(
			ContentHandler<? extends ContentSession> handler,
			ContentSessionManager manager, AsyncContext asyncContext,
			String contentId, boolean redirect, boolean useControlProtocol) {
		super(handler, manager, asyncContext, contentId);
		this.useControlProtocol = useControlProtocol;
		this.redirect = redirect;
		if (!useControlProtocol) {
			state = STATE.HANDLING;
		}
	}

	/**
	 * Build media element (such as player, recorder, and so on) in the media
	 * server.
	 * 
	 * @param contentPath
	 *            Content path in which build the media element
	 * @return Created media element
	 */
	protected abstract UriEndPoint buildUriEndPoint(String contentPath);

	/**
	 * 
	 * @param mediaElements
	 *            must be non-null and non-empty
	 * @return
	 */
	protected abstract HttpEndPoint buildAndConnectHttpEndPoint(
			MediaElement... mediaElements);

	/*
	 * This is an utility method designed for minimizing code replication. For
	 * it to work, one and only one of the two parameters must be null;
	 */
	protected void activateMedia(String contentPath,
			MediaElement... mediaElements) {
		synchronized (this) {
			Assert.isTrue(
					state == STATE.HANDLING,
					"Cannot start media exchange in state "
							+ state
							+ ". This error means a violatiation in the content session lifecycle",
					10001);
			state = STATE.STARTING;
		}

		final boolean mediaElementProvided = mediaElements != null
				&& mediaElements.length > 0;
		final boolean contentPathProvided = contentPath != null;

		Assert.isTrue(
				mediaElementProvided || contentPathProvided,
				"Internal error. Cannot process request containing two null parameters",
				10002);
		Assert.isTrue(
				!(mediaElementProvided && contentPathProvided),
				"Internal error. Cannot process request containing two non null parameters",
				10003);

		getLogger().info(
				"Activating media for " + this.getClass().getSimpleName()
						+ " with contentPath " + contentPath);

		if (contentPath != null) {
			mediaElements = new MediaElement[1];
			uriEndPoint = buildUriEndPoint(contentPath);
			mediaElements[0] = uriEndPoint;
		}

		HttpEndPoint httpEndPoint = buildAndConnectHttpEndPoint(mediaElements);

		// We need to assert that session was not rejected while we were
		// creating media infrastructure
		boolean terminate = false;
		synchronized (this) {
			if (state == STATE.TERMINATED) {
				terminate = true;
			} else if (state == STATE.STARTING) {
				state = STATE.ACTIVE;
			}
		}

		// If session was rejected, just terminate
		if (terminate) {
			getLogger()
					.info("Exiting due to terminate ... this should only happen on client's explicit termination");
			destroy(); // idempotent call. Just in case pipeline gets build
						// after session executes termination
			return;
		}

		// If session was not rejected (state=ACTIVE) we send an answer and
		// the initialAsyncCtx becomes useless
		String answerUrl = httpEndPoint.getUrl();
		getLogger().info("HttpEndPoint.getUrl = " + answerUrl);

		Assert.notNull(answerUrl, "Received null url from HttpEndPoint", 20012);
		Assert.isTrue(answerUrl.length() > 0,
				"Received invalid empty url from media server", 20012);

		// Manage fatal errors occurring in the pipeline
		httpEndPoint.getMediaPipeline().addErrorListener(
				new MediaErrorListener() {
					@Override
					public void onError(MediaError error) {
						getLogger().error(error.getDescription()); // TODO:
																	// improve
																	// message
						internalTerminateWithError(null, error.getErrorCode(),
								error.getDescription(), null);
					}
				});

		// Generate appropriate actions when content is started
		httpEndPoint
				.addMediaSessionStartListener(new MediaEventListener<MediaSessionStartedEvent>() {
					@Override
					public void onEvent(MediaSessionStartedEvent event) {
						callOnContentStartedOnHanlder();
						getLogger().info(
								"Received event with type " + event.getType());
						if (uriEndPoint != null
								&& uriEndPoint instanceof PlayerEndPoint) {
							((PlayerEndPoint) uriEndPoint).play();
						} else if (uriEndPoint != null
								&& uriEndPoint instanceof RecorderEndPoint) {
							((RecorderEndPoint) uriEndPoint).record();
							// TODO: ask Jose if this may produce losses in
							// recorder
						}
					}
				});

		// Generate appropriate actions when media session is terminated
		httpEndPoint
				.addMediaSessionTerminatedListener(new MediaEventListener<MediaSessionTerminatedEvent>() {
					@Override
					public void onEvent(MediaSessionTerminatedEvent event) {
						getLogger().info(
								"Received event with type " + event.getType());
						internalTerminateWithoutError(null, 1,
								"MediaServer MediaSessionTerminated", null); // TODO

					}
				});

		if (useControlProtocol) {
			answerActivateMediaRequest4JsonControlProtocolConfiguration(answerUrl);
		} else {
			answerActivateMediaRequest4SimpleHttpConfiguration(answerUrl);
		}

	}

	/**
	 * Provide an HTTP response, depending of which redirect strategy is used:
	 * it could a redirect (HTTP 307, Temporary Redirect), or a tunneled
	 * response using the Streaming Proxy.
	 * 
	 * @param url
	 *            Content URL
	 * @throws ContentException
	 *             Exception in the media server
	 */
	private void answerActivateMediaRequest4SimpleHttpConfiguration(String url) {
		try {
			HttpServletResponse response = (HttpServletResponse) initialAsyncCtx
					.getResponse();
			HttpServletRequest request = (HttpServletRequest) initialAsyncCtx
					.getRequest();
			if (redirect) {
				getLogger().info("Sending redirect to " + url);
				response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
				response.setHeader("Location", url);
			} else {
				getLogger().info("Activating tunneling proxy to " + url);
				tunnellingProxyFuture = proxy.tunnelTransaction(request,
						response, url, new StreamingProxyListener() {

							@Override
							public void onProxySuccess() {
								tunnellingProxyFuture = null;
								// Parameters no matter, no answer will be sent
								// given that we are already in ACTIVE state
								terminate(0, "");
							}

							@Override
							public void onProxyError(String message,
									int errorCode) {
								tunnellingProxyFuture = null;
								// Parameters no matter, no answer will be sent
								// given that we are already in ACTIVE state
								terminate(errorCode, message);
							}
						});
			}
		} catch (Throwable t) {
			throw new KurentoMediaFrameworkException(t.getMessage(), t, 20013);
		} finally {
			if (redirect) {
				initialAsyncCtx.complete();
			}
			initialAsyncCtx = null;
		}
	}

	/**
	 * Provide an HTTP response, when a JSON signaling protocol strategy is
	 * used.
	 * 
	 * @param url
	 *            Content URL
	 * @throws ContentException
	 *             Exception in the media server
	 */
	private void answerActivateMediaRequest4JsonControlProtocolConfiguration(
			String url) {
		protocolManager.sendJsonAnswer(initialAsyncCtx,
				JsonRpcResponse.newStartUrlResponse(url, sessionId,
						initialJsonRequest.getId()));
		initialAsyncCtx = null;
		initialJsonRequest = null;
	}

	/**
	 * Control protocol accessor (getter).
	 * 
	 * @return Control protocol strategy
	 */
	@Override
	public boolean useControlProtocol() {
		return useControlProtocol;
	}

	@Override
	protected void sendErrorAnswerOnInitialContext(int code, String description) {
		if (useControlProtocol) {
			super.sendErrorAnswerOnInitialContext(code, description);
		} else {
			try {
				ServletUtils.sendHttpError(
						(HttpServletRequest) initialAsyncCtx.getRequest(),
						(HttpServletResponse) initialAsyncCtx.getResponse(),
						ExceptionUtils.getHttpErrorCode(code), description);
			} catch (ServletException e) {
				getLogger().error(e.getMessage(), e);
				throw new KurentoMediaFrameworkException(e, 20026);
			}
		}
	}

	@Override
	protected void sendRejectOnInitialContext(int code, String description) {
		if (useControlProtocol) {
			super.sendRejectOnInitialContext(code, description);
		} else {
			try {
				ServletUtils.sendHttpError(
						(HttpServletRequest) initialAsyncCtx.getRequest(),
						(HttpServletResponse) initialAsyncCtx.getResponse(),
						ExceptionUtils.getHttpErrorCode(code), description);
			} catch (ServletException e) {
				getLogger().error(e.getMessage(), e);
				throw new KurentoMediaFrameworkException(e, 20026);
			}
		}
	}

	/**
	 * Release Streaming proxy.
	 */
	@Override
	protected void destroy() {
		super.destroy();

		Future<?> localTunnelingProxyFuture = tunnellingProxyFuture;
		if (localTunnelingProxyFuture != null) {
			localTunnelingProxyFuture.cancel(true);
			tunnellingProxyFuture = null;
		}
	}
}
