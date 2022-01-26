var cwd = process.cwd();
var WebSocketClient = require('websocket').client;
var size = 4400;
var authInterval = 10;
var index = 0;
setInterval(function () {
	if(index < size) {
		uid = index;
		cid = index;
		init(uid, cid);
		index++;
	}
}, authInterval);
console.log('begin...');
init = function (uid, cid) {
	var client = new WebSocketClient();
	client.connect('ws://localhost:8888/ws', "", "http://localhost:8888");
	client.on('connectFailed', function (error) {
		console.log('Connect Error: ' + error.toString());
	});
	client.on('connect', function (connection) {
		console.log(index + ' Connected');
		connection.send('{"type": "match" }')
		connection.on('error', function (error) {
			console.log("Connection Error: " + error.toString());
		});
		connection.on('close', function (error) {
			console.log(error + ';  Connection Closed');            //client.close();
		});
		connection.on('message', function (message) {
			console.log("message: " + JSON.stringify(message));
		});
	});
};

function timeLogout() {
	return setTimeout(function () {
		logout(uid);
	}, StartTime);
}
