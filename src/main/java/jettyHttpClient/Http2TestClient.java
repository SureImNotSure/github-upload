package jettyHttpClient;

import java.net.InetSocketAddress;
import java.util.concurrent.Phaser;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.Stream.Listener;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Http2TestClient {

	static final Logger LOG_HTTP2CON = Log.getLogger(HTTP2Connection.class);
	static final Logger LOG_HTTP2SES = Log.getLogger(HTTP2Session.class);
	static final Logger LOG_HTTP2STR = Log.getLogger(HTTP2Stream.class);

	public static void main(String[] args) throws Exception {
		//		LOG_HTTP2CON.setDebugEnabled(true);
		//		LOG_HTTP2SES.setDebugEnabled(true);
		//		LOG_HTTP2STR.setDebugEnabled(true);

		HTTP2Client client = new HTTP2Client();
		client.setConnectTimeout(1000 * 3);
		client.setIdleTimeout(-1);
		client.setStopTimeout(-1);
		client.start();

		String host = "localhost";
		int port = 5443;

		FuturePromise<Session> sessionPromise = new FuturePromise<>();
		client.connect(new InetSocketAddress(host, port), new ServerSessionListener.Adapter(), sessionPromise);
		//		Session session = sessionPromise.get(5, TimeUnit.SECONDS);
		Session session = sessionPromise.get();

		HttpFields requestFields = new HttpFields();
		requestFields.put("User-Agent", client.getClass().getName() + "/" + Jetty.VERSION);
		MetaData.Request metaData = new MetaData.Request("GET", new HttpURI("http://" + host + ":" + port + "/"),
				HttpVersion.HTTP_2, requestFields);
		System.out.println(metaData.getURIString());
		HeadersFrame headersFrame = new HeadersFrame(0, metaData, null, true);
		final Phaser phaser = new Phaser(2);

		class LocalListener extends Stream.Listener.Adapter {

			private boolean isStopping = false;
			@SuppressWarnings("unused")
			public boolean isStopping() {
				return isStopping;
			}
			private void setStopping(boolean stopping) {
				isStopping = stopping;
			}

			@Override
			public void onHeaders(Stream stream, HeadersFrame frame) {

				System.out.println("[" + stream.getId() + "] HEADERS " + frame.getMetaData().toString());
				if (frame.isEndStream()) {
					phaser.arrive();
				}
			}

			@Override
			public void onData(Stream stream, DataFrame frame, Callback callback) {
				byte[] bytes = new byte[frame.getData().remaining()];
				frame.getData().get(bytes);
				String receiveData = new String(bytes);
				System.out.println("[" + stream.getId() + "] DATA " + receiveData);

				if (receiveData.contains("q")) {
					setStopping(true);
				}

				callback.succeeded();
				if (frame.isEndStream()) {
					phaser.arrive();
				}
			}

			@Override
			public Stream.Listener onPush(Stream stream, PushPromiseFrame frame) {
				System.out.println("[" + stream.getId() + "] PUSH_PROMISE " + frame.getMetaData().toString());
				phaser.register();
				return this;
			}

			@Override
			public void onReset(Stream stream, ResetFrame frame) {
				System.out.println("stream.getIdleTimeout " + stream.getIdleTimeout());
				System.out.println("[" + stream.getId() + "] reset " + frame.getError());
				System.out.println("[" + stream.getId() + "] " + stream.toString());
			}

			@Override
			public void onTimeout(Stream stream, Throwable x) {
				System.out.println("timeout");
			}

			@Override
			public boolean onIdleTimeout(Stream stream, Throwable x) {
				System.out.println("idle timeout");
				return true;
			}

		};

		while (true) {
			Listener listener = new LocalListener();
			session.newStream(headersFrame, new Promise.Adapter<Stream>(), listener);
			System.out.println("Phaser waiting.");
			phaser.awaitAdvanceInterruptibly(phaser.arrive());
			System.out.println("Phaser arrived.");

			if (((LocalListener)listener).isStopping) {
				break;
			}
		}

		client.stop();
	}

}
