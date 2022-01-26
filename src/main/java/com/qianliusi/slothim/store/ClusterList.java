package com.qianliusi.slothim.store;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.shareddata.impl.ClusterSerializable;

import java.util.ArrayList;
import java.util.List;

public class ClusterList<E extends ClusterSerializable> extends ArrayList<E> implements ClusterSerializable {
	@Override
	public void writeToBuffer(Buffer buffer) {
		Buffer buf = Json.CODEC.toBuffer(this, false);
		buffer.appendInt(buf.length());
		buffer.appendBuffer(buf);
	}

	@Override
	public int readFromBuffer(int pos, Buffer buffer) {
		int length = buffer.getInt(pos);
		int start = pos + 4;
		Buffer buf = buffer.getBuffer(start, start + length);
		Json.CODEC.fromBuffer(buf, List.class);
		return pos + length + 4;
	}
}
