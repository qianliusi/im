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
		server.requestHandler(router).webSocketHandler(this::webSocketHandler).listen(config().getInteger("port", 8888));
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
					Promise<String> matchUser = matchUser(userId);
					matchUser.future().onSuccess(token -> {
						MsgMessage matchedMsg = new MsgMessage(MsgTypeEnum.matched.code());
						matchedMsg.setContent(token);
						webSocket.writeTextMessage(JSON.toJSONString(matchedMsg));
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
			closeConnection(userId);
			consumer.unregister();
			logger.error("WebSocket服务异常", e);
		});
	}

	private Promise<String> matchUser(String userToken) {
		Promise<String> promise = Promise.promise();
		updateUserState(userToken, UserStateEnum.matching);
		vertx.setPeriodic(1000, id -> {
			Promise<String> matchedUser = doMatchUser(userToken);
			matchedUser.future().onSuccess(t->{
				promise.complete(t);
				vertx.cancelTimer(id);
			});
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
			int onlineUserNum = a.keys().result().size();
			promise.complete(onlineUserNum);
		}));
		return promise;
	}

	private boolean validMatch(MsgUser u,String token) {
		return !u.getId().equals(token) && UserStateEnum.matching.code().equals(u.getState());
	}

	private Promise<String> doMatchUser(String token) {
		Promise<String> promise = Promise.promise();
		Future<AsyncMap<String, MsgUser>> onlineUser = getOnlineUser();
		onlineUser.onSuccess(asyncMap-> {
			asyncMap.values().onSuccess(event -> {
				logger.info("online user{}", JSON.toJSONString(event));
			});
			//检查自己有没有被别人匹配
			asyncMap.get(token).onSuccess(matchingUser->{
				if(UserStateEnum.matched.code().equals(matchingUser.getState())) {
					promise.complete(matchingUser.getPartner());
				}else {
					asyncMap.values().onSuccess(list -> {
						MsgUser matchedUser = list.stream().filter(a -> validMatch(a, token)).findAny().orElse(null);
						if(matchedUser == null) {
							promise.fail("无配对中用户");
						}else {
							matchingUser.setState(UserStateEnum.matched.code());
							matchingUser.setPartner(matchedUser.getId());
							asyncMap.put(matchingUser.getId(), matchingUser);
							matchedUser.setState(UserStateEnum.matched.code());
							matchedUser.setPartner(matchingUser.getId());
							asyncMap.put(matchedUser.getId(), matchedUser);
							promise.complete(matchedUser.getId());
						}
					});
				}
			});
		}).onFailure(promise::fail);
		return promise;
	}

	private Future<AsyncMap<String, MsgUser>> getOnlineUser(){
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
		Future<AsyncMap<String, MsgUser>> mapFuture = getOnlineUser();
		mapFuture.onSuccess(asyncMap-> asyncMap.get(userId, h->{
			if (h.succeeded()) {
				MsgUser user = h.result();
				logger.debug("远程连接关闭，token为：" + user);
				//删除Map中token和Websocket的对应关系
				asyncMap.remove(userId, rem->{
					if (rem.succeeded()){
						logger.debug("删除Map中token和Websocket的对应关系");
					} else {
						logger.error("关闭websocket连接异常", rem.cause());
					}
				});
				//创建一个关闭连接的消息
				MsgMessage message = new MsgMessage(MsgTypeEnum.leave.code());
				if(UserStateEnum.matched.code().equals(user.getState())) {
					vertx.eventBus().send(user.getPartner(), Buffer.buffer(JSON.toJSONBytes(message)));
				}
			} else {
				logger.error("关闭websocket连接异常", h.cause());
			}
		})).onFailure(e->{
			logger.error("关闭websocket连接异常", e);
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
			closeConnectionRoom(roomId,userId);
			consumer.unregister();
			logger.error("WebSocket服务异常", e);
		});
	}

}
