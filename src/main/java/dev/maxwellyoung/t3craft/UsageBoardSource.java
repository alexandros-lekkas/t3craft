package dev.maxwellyoung.t3craft;

import java.util.concurrent.atomic.AtomicBoolean;

/** Rate-limited usage snapshot reader. Calls run on T3State's existing I/O executor. */
final class UsageBoardSource {
	private static final long INTERVAL_MS=60_000;
	private final T3State state;
	private volatile UsageBoardData data;
	private volatile long lastRequest;
	private final AtomicBoolean logged=new AtomicBoolean();
	UsageBoardSource(T3State state){this.state=state;}
	UsageBoardData data(){return data;}
	void refresh(){long now=System.currentTimeMillis();if(now-lastRequest<INTERVAL_MS)return;lastRequest=now;
		state.run(()->{T3Backend backend=state.api();if(backend==null)throw new java.io.IOException("No T3 pairing");UsageBoardData next=UsageBoardData.fromServerConfig(backend.usageConfig());data=next;logged.set(false);},e->{if(logged.compareAndSet(false,true))T3Log.LOGGER.warn("Could not refresh usage board data: {}",e.getMessage());});
	}
}
