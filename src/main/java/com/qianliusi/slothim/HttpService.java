package com.qianliusi.slothim;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.qianliusi.slothim.enums.MsgTypeEnum;
import com.qianliusi.slothim.enums.UserStateEnum;
import com.qianliusi.slothim.message.MsgMessage;
import com.qianliusi.slothim.store.MsgUser;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class HttpService extends AbstractVerticle {
	private static Logger logger = LoggerFactory.getLogger(HttpService.class);
	@Override
	public void start() {
		HttpServer server = vertx.createHttpServer();
		Router router = Router.router(vertx);
		router.route("/static/*").handler(StaticHandler.create());
		router.get("/").handler(ctx -> ctx.reroute("/static/index.html"));
		router.get("/room").handler(ctx -> ctx.reroute("/static/room.html"));
		server.requestHandler(router).webSocketHandler(this::webSocketHandler).listen(config().getInteger("port", 38888));
	}

	public void webSocketHandler(ServerWebSocket webSocket) {
		// 接收客户端连接
		String path = webSocket.path();
		if (path.equals("/ws")) {
			webSocketHandlerChat(webSocket);
			return;
		}
		if (path.contains("room")) {
			webSocketHandlerRoom(webSocket, StrUtil.subAfter(path,"/",true));
			return;
		}
		logger.info("websocket路径[{}]非法！拒绝连接！", path);
		webSocket.reject();
	}


	public void webSocketHandlerChat(ServerWebSocket webSocket) {
		//保存在线用户
		String userId = UUID.randomUUID().toString();
		logger.info("webSocket Connected！[{}]",userId);
		putUser(userId).future().onSuccess(event -> {
			MsgMessage tokenMsg = new MsgMessage(MsgTypeEnum.token.code(), userId);
			webSocket.writeTextMessage(JSON.toJSONString(tokenMsg));
			MsgMessage joinMsg = new MsgMessage(MsgTypeEnum.join.code());
			joinMsg.setContent(event + "");
			webSocket.writeTextMessage(JSON.toJSONString(joinMsg));
		});
		MessageConsumer<Buffer> consumer = eventBusConsumer(webSocket,userId);
		// websocket接收到消息就会调用此方法
		webSocket.handler(buffer->{
			MsgMessage msg = JSON.parseObject(buffer.getBytes(), MsgMessage.class);
			logger.info("WebSocket receive msg[{}]",msg);
			MsgTypeEnum msgTypeEnum = MsgTypeEnum.valueOf(msg.getType());
			switch(msgTypeEnum) {
				case match:
					Promise<String> matchUser = matchUser(userId,webSocket);
					matchUser.future().onSuccess(token -> {
						if(token != null) {
							MsgMessage matchedMsg = new MsgMessage(MsgTypeEnum.matched.code());
							matchedMsg.setContent(token);
							webSocket.writeTextMessage(JSON.toJSONString(matchedMsg));
						}
					});
					break;
				case chat:
					webSocket.writeTextMessage(buffer.toString());
					vertx.eventBus().send(msg.getReceiver(), buffer);
					break;
				case leave:
					updateUserState(userId, UserStateEnum.idle);
					MsgMessage closeMsg = new MsgMessage(MsgTypeEnum.leave.code());
					vertx.eventBus().send(msg.getReceiver(), Buffer.buffer(JSON.toJSONBytes(closeMsg)));
					break;
			}
		});
		// 当连接关闭后就会调用此方法
		webSocket.closeHandler(event -> {
			closeConnection(userId);
			consumer.unregister();
		});
		// WebSocket异常处理器
		webSocket.exceptionHandler(e->{
//			closeConnection(userId);
//			consumer.unregister();
			logger.error("WebSocket服务异常", e);
		});
	}
	private Promise<String> matchUser(String userId,ServerWebSocket webSocket) {
		Promise<String> promise = Promise.promise();
		updateUserState(userId, UserStateEnum.matching);
		vertx.eventBus().request("matchUser", userId, reply -> {
			if(reply.succeeded()) {
				JsonObject replyJson = (JsonObject) reply.result().body();
				String state = replyJson.getString("state");
				String result = replyJson.getString("result");
				String matchedUserId = null;
				if("success".equals(state)) {
					matchedUserId = result;
				}
				promise.complete(matchedUserId);
				if("fail".equals(state)) {
					getUser(userId).future().onSuccess(u -> {
						if(u != null && UserStateEnum.matching.code().equals(u.getState()) && !webSocket.isClosed()) {
							MsgMessage matchedMsg = new MsgMessage(MsgTypeEnum.matchTimeout.code(),result);
							webSocket.writeTextMessage(JSON.toJSONString(matchedMsg));
						}
					});
				}
			}
		});
		return promise;
	}

	private Promise<Void> updateUserState(String userId, UserStateEnum state) {
		Promise<Void> promise = Promise.promise();
		Future<AsyncMap<String, MsgUser>> onlineUser = getOnlineUser();
		onlineUser.onSuccess(a -> a.get(userId).onSuccess(u -> {
			u.setState(state.code());
			a.put(userId, u, event -> promise.complete(event.result()));
		}));
		return promise;
	}

	private Promise<Integer> putUser(String userId) {
		Promise<Integer> promise = Promise.promise();
		Future<AsyncMap<String, MsgUser>> onlineUser = getOnlineUser();
		onlineUser.onSuccess(a -> a.put(userId, new MsgUser(userId, UserStateEnum.idle.code()), event -> {
			a.values().onSuccess(num -> {
				promise.complete(num.size());
				//打印在线人数
				long pairedNum = num.stream().filter(u -> UserStateEnum.matched.code().equals(u.getState())).count();
				logger.info("online user size [{}]，pair size [{}]", num.size(), pairedNum);
			});
		}));
		return promise;
	}
	private Promise<MsgUser> getUser(String userId) {
		Promise<MsgUser> promise = Promise.promise();
		Future<AsyncMap<String, MsgUser>> onlineUser = getOnlineUser();
		onlineUser.onSuccess(a -> a.get(userId).onSuccess(promise::complete));
		return promise;
	}

	private Promise<MsgUser> removeUser(String userId) {
		Promise<MsgUser> promise = Promise.promise();
		Future<AsyncMap<String, MsgUser>> onlineUser = getOnlineUser();
		onlineUser.onSuccess(a -> a.get(userId).onSuccess(u -> {
			a.remove(userId);
			promise.complete(u);
		}));
		return promise;
	}

	public Future<AsyncMap<String, MsgUser>> getOnlineUser(){
		// 取Websocket和token之间的对应关系
		SharedData sd = vertx.sharedData();
		if (vertx.isClustered()) {
			return sd.getClusterWideMap("chatUser");
		}
		return sd.getAsyncMap("chatUser");
	}

	private Future<AsyncMap<String, List<MsgUser>>> getRoomOnlineUser(){
		// 取Websocket和token之间的对应关系
		SharedData sd = vertx.sharedData();
		if (vertx.isClustered()) {
			return sd.getClusterWideMap("roomUser");
		}
		return sd.getAsyncMap("roomUser");
	}

	private void closeConnection(String userId) {
		logger.info("WebSocket closed userId[{}]",userId);
		removeUser(userId).future().onSuccess(user -> {
			//创建一个关闭连接的消息
			if(UserStateEnum.matched.code().equals(user.getState())) {
				MsgMessage message = new MsgMessage(MsgTypeEnum.leave.code());
				vertx.eventBus().send(user.getPartner(), Buffer.buffer(JSON.toJSONBytes(message)));
			}
		});
	}

	private void closeConnectionRoom(String roomId,String userId) {
		logger.info("WebSocket closed roomId [{}],userId[{}]",roomId,userId);
		removeRoomUser(roomId, userId).future().onSuccess(roomUserNum -> {
			MsgMessage leaveMsg = new MsgMessage(MsgTypeEnum.leave.code());
			leaveMsg.setContent(roomUserNum + "");
			vertx.eventBus().publish(roomId, Buffer.buffer(JSON.toJSONBytes(leaveMsg)));
		});
	}


	public MessageConsumer<Buffer> eventBusConsumer(ServerWebSocket webSocket, String userId) {
		//注册消费者
		MessageConsumer<Buffer> consumer = vertx.eventBus().consumer(userId);
		consumer.handler(msg->{
			//接收到消息
			MsgMessage message = JSON.parseObject(msg.body().getBytes(), MsgMessage.class);
			String type = message.getType();
			if (MsgTypeEnum.leave.code().equals(type)) {
				updateUserState(userId, UserStateEnum.idle);
			}
			webSocket.writeTextMessage(JSON.toJSONString(message));
		});
		return consumer;
	}

	public MessageConsumer<Buffer> eventBusConsumerRoom(ServerWebSocket webSocket, String roomId) {
		//注册消费者
		MessageConsumer<Buffer> consumer = vertx.eventBus().consumer(roomId);
		consumer.handler(msg->{
			//接收到消息
			MsgMessage message = JSON.parseObject(msg.body().getBytes(), MsgMessage.class);
			webSocket.writeTextMessage(JSON.toJSONString(message));
		});
		return consumer;
	}


	private Promise<Integer> putRoomUser(String roomId,String userId) {
		Promise<Integer> promise = Promise.promise();
		Future<AsyncMap<String, List<MsgUser>>> onlineUser = getRoomOnlineUser();
		onlineUser.onSuccess(a->a.get(roomId).onSuccess(event -> {
			if(event == null) {
				event = new ArrayList<>();
			}
			event.add(new MsgUser(userId, null));
			int roomUserNum = event.size();
			a.put(roomId, event, e -> promise.complete(roomUserNum));
		}));
		return promise;
	}

	private Promise<Integer> removeRoomUser(String roomId,String userId) {
		Promise<Integer> promise = Promise.promise();
		Future<AsyncMap<String, List<MsgUser>>> onlineUser = getRoomOnlineUser();
		onlineUser.onSuccess(a->a.get(roomId).onSuccess(event -> {
			if(event != null) {
				event = event.stream().filter(user -> !userId.equals(user.getId())).collect(Collectors.toList());
				int roomUserNum = event.size();
				a.put(roomId, event,e -> promise.complete(roomUserNum));
			}
		}));
		return promise;
	}

	public void webSocketHandlerRoom(ServerWebSocket webSocket,String roomId) {
		//保存在线用户
		String userId = UUID.randomUUID().toString();
		logger.info("websocket connected, roomId[{}],userId[{}]",roomId,userId);
		MessageConsumer<Buffer> consumer = eventBusConsumerRoom(webSocket,roomId);

		putRoomUser(roomId,userId).future().onSuccess(event -> {
			MsgMessage tokenMsg = new MsgMessage(MsgTypeEnum.token.code(), userId);
			webSocket.writeTextMessage(JSON.toJSONString(tokenMsg));
			MsgMessage joinMsg = new MsgMessage(MsgTypeEnum.join.code());
			joinMsg.setContent(event + "");
			vertx.eventBus().publish(roomId, Buffer.buffer(JSON.toJSONBytes(joinMsg)));
		});
		// websocket接收到消息就会调用此方法
		webSocket.handler(buffer->{
			MsgMessage msg = JSON.parseObject(buffer.getBytes(), MsgMessage.class);
			logger.info("WebSocket receive msg[{}]",msg);
			MsgTypeEnum msgTypeEnum = MsgTypeEnum.valueOf(msg.getType());
			switch(msgTypeEnum) {
				case chat:
//					webSocket.writeTextMessage(buffer.toString());
					vertx.eventBus().publish(roomId, buffer);
					break;
				case leave:
					removeRoomUser(roomId, userId).future().onSuccess(roomUserNum -> {
						MsgMessage leaveMsg = new MsgMessage(MsgTypeEnum.leave.code());
						leaveMsg.setContent(roomUserNum + "");
						vertx.eventBus().publish(roomId, Buffer.buffer(JSON.toJSONBytes(leaveMsg)));
					});
					break;
			}
		});
		// 当连接关闭后就会调用此方法
		webSocket.closeHandler(event -> {
			closeConnectionRoom(roomId,userId);
			consumer.unregister();
		});
		// WebSocket异常处理器
		webSocket.exceptionHandler(e->{
//			closeConnectionRoom(roomId,userId);
//			consumer.unregister();
			logger.error("WebSocket服务异常", e);
		});
	}

}
