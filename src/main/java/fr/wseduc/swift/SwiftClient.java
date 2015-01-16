/*
 * Copyright © WebServices pour l'Éducation, 2014
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

package fr.wseduc.swift;

import fr.wseduc.swift.exception.AuthenticationException;
import fr.wseduc.swift.exception.StorageException;
import fr.wseduc.swift.storage.DefaultAsyncResult;
import fr.wseduc.swift.storage.StorageObject;
import fr.wseduc.swift.utils.*;
import org.vertx.java.core.*;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.*;
import org.vertx.java.core.json.JsonObject;

import java.net.URI;
import java.util.UUID;

public class SwiftClient {

	private final HttpClient httpClient;
	private final String account;
	private final String defaultContainer;
	private String token;

	public SwiftClient(Vertx vertx, URI uri, String account) {
		this(vertx, uri, account, "documents");
	}

	public SwiftClient(Vertx vertx, URI uri, String account, String container) {
		this.httpClient = vertx.createHttpClient()
				.setHost(uri.getHost())
				.setPort(uri.getPort())
				.setMaxPoolSize(16)
				.setKeepAlive(false);
		this.account = account;
		this.defaultContainer = container;
	}

	public void authenticate(java.lang.String user, java.lang.String password, final AsyncResultHandler<Void> handler) {
		HttpClientRequest req = httpClient.get("/auth/v1.0", new Handler<HttpClientResponse>() {
			@Override
			public void handle(HttpClientResponse response) {
				if (response.statusCode() == 200) {
					token = response.headers().get("X-Storage-Token");
					handler.handle(new DefaultAsyncResult<>((Void) null));
				} else {
					handler.handle(new DefaultAsyncResult<Void>(new AuthenticationException(response.statusMessage())));
				}
			}
		});
		req.putHeader("X-Auth-User", user);
		req.putHeader("X-Auth-Key", password);
		req.end();
	}

	public void uploadFile(final HttpServerRequest request, final Handler<JsonObject> handler) {
		uploadFile(request, defaultContainer, handler);
	}

	public void uploadFile(final HttpServerRequest request, final String container, final Handler<JsonObject> handler) {
		request.expectMultiPart(true);
		request.uploadHandler(new Handler<HttpServerFileUpload>() {
			@Override
			public void handle(final HttpServerFileUpload upload) {
				upload.pause();
				final JsonObject metadata = FileUtils.metadata(upload);
				final java.lang.String id = UUID.randomUUID().toString();
				final HttpClientRequest req = httpClient.put("/v1/" + account + "/" + container + "/" + id,
						new Handler<HttpClientResponse>() {
							@Override
							public void handle(HttpClientResponse response) {
								if (response.statusCode() == 201) {
									handler.handle(new JsonObject().putString("_id", id)
											.putString("status", "ok")
											.putObject("metadata", metadata));
								} else {
									handler.handle(new JsonObject().putString("status", "error"));
								}
							}
						});
				req.putHeader("X-Storage-Token", token);
				req.putHeader("Content-Type", metadata.getString("content-type"));
				req.putHeader("X-Object-Meta-Filename", metadata.getString("filename"));
				req.setChunked(true);
				upload.dataHandler(new Handler<Buffer>() {
					public void handle(Buffer data) {
						req.write(data);
					}
				});
				upload.endHandler(new VoidHandler() {
					public void handle() {
						req.end();
					}
				});
				upload.resume();
			}
		});
	}

	public void downloadFile(String id, final HttpServerRequest request) {
		downloadFile(id, request, defaultContainer, true, null, null, null);
	}

	public void downloadFile(String id, String container, final HttpServerRequest request) {
		downloadFile(id, request, container, true, null, null, null);
	}

	public void downloadFile(String id, final HttpServerRequest request,
		 boolean inline, String downloadName, JsonObject metadata, final String eTag) {
		downloadFile(id, request, defaultContainer, inline, downloadName, metadata, eTag);
	}

	public void downloadFile(String id, final HttpServerRequest request, String container,
			boolean inline, String downloadName, JsonObject metadata, final String eTag) {
		final HttpServerResponse resp = request.response();
		if (!inline) {
			java.lang.String name = FileUtils.getNameWithExtension(downloadName, metadata);
			resp.putHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
		}
		HttpClientRequest req = httpClient.get("/v1/" + account + "/" + container + "/" + id, new Handler<HttpClientResponse>() {
			@Override
			public void handle(HttpClientResponse response) {
				response.pause();
				if (response.statusCode() == 200 || response.statusCode() == 304) {
					resp.putHeader("ETag", ((eTag != null) ? eTag : response.headers().get("ETag")));
				}
				if (response.statusCode() == 200) {
					resp.setChunked(true);
					response.dataHandler(new Handler<Buffer>() {
						@Override
						public void handle(Buffer event) {
							resp.write(event);
						}
					});
					response.endHandler(new Handler<Void>() {
						@Override
						public void handle(Void event) {
							resp.end();
						}
					});
					response.resume();
				} else {
					resp.setStatusCode(response.statusCode()).setStatusMessage(response.statusMessage()).end();
				}
			}
		});
		req.putHeader("X-Storage-Token", token);
		req.putHeader("If-None-Match", request.headers().get("If-None-Match"));
		req.end();
	}

	public void readFile(final String id, final AsyncResultHandler<StorageObject> handler) {
		readFile(id, defaultContainer, handler);
	}

	public void readFile(final String id, String container, final AsyncResultHandler<StorageObject> handler) {
		HttpClientRequest req = httpClient.get("/v1/" + account + "/" + container + "/" + id, new Handler<HttpClientResponse>() {
			@Override
			public void handle(final HttpClientResponse response) {
				response.pause();
				if (response.statusCode() == 200) {
					final Buffer buffer = new Buffer();
					response.dataHandler(new Handler<Buffer>() {
						@Override
						public void handle(Buffer event) {
							buffer.appendBuffer(event);
						}
					});
					response.endHandler(new Handler<Void>() {
						@Override
						public void handle(Void event) {
							StorageObject o = new StorageObject(
									id,
									buffer,
									response.headers().get("X-Object-Meta-Filename"),
									response.headers().get("Content-Type")
							);
							handler.handle(new DefaultAsyncResult<>(o));
						}
					});
					response.resume();
				} else {
					handler.handle(new DefaultAsyncResult<StorageObject>(new StorageException(response.statusMessage())));
				}
			}
		});
		req.putHeader("X-Storage-Token", token);
		req.end();
	}

	public void writeFile(StorageObject object, final AsyncResultHandler<String> handler) {
		writeFile(object, defaultContainer, handler);
	}

	public void writeFile(StorageObject object, String container, final AsyncResultHandler<String> handler) {
		final String id = (object.getId() != null) ? object.getId() : UUID.randomUUID().toString();
		final HttpClientRequest req = httpClient.put("/v1/" + account + "/" + container + "/" + id,
				new Handler<HttpClientResponse>() {
					@Override
					public void handle(HttpClientResponse response) {
						if (response.statusCode() == 201) {
							handler.handle(new DefaultAsyncResult<>(id));
						} else {
							handler.handle(new DefaultAsyncResult<String>(new StorageException(response.statusMessage())));
						}
					}
				});
		req.putHeader("X-Storage-Token", token);
		req.putHeader("Content-Type", object.getContentType());
		req.putHeader("X-Object-Meta-Filename", object.getFilename());
		req.end(object.getBuffer());
	}

	public void close() {
		if (httpClient != null) {
			httpClient.close();
		}
	}

}
