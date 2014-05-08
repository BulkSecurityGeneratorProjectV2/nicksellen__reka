package reka.builtins;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static reka.api.Path.dots;
import static reka.api.Path.root;
import static reka.api.content.Contents.binary;
import static reka.configurer.Configurer.configure;
import static reka.configurer.Configurer.Preconditions.checkConfig;
import static reka.core.builder.FlowSegments.async;
import static reka.core.builder.FlowSegments.halt;
import static reka.core.builder.FlowSegments.label;
import static reka.core.builder.FlowSegments.par;
import static reka.core.builder.FlowSegments.sequential;
import static reka.core.builder.FlowSegments.sync;
import static reka.core.config.ConfigUtils.configToData;
import static reka.util.Util.createEntry;
import static reka.util.Util.runtime;
import static reka.util.Util.unchecked;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reka.EmbeddedFlowConfigurer;
import reka.api.Path;
import reka.api.Path.Response;
import reka.api.content.Content;
import reka.api.data.Data;
import reka.api.data.MutableData;
import reka.api.flow.FlowSegment;
import reka.api.run.AsyncOperation;
import reka.api.run.RouteCollector;
import reka.api.run.RoutingOperation;
import reka.api.run.SyncOperation;
import reka.config.Config;
import reka.config.ConfigBody;
import reka.configurer.Configurer.ErrorCollector;
import reka.configurer.ErrorReporter;
import reka.configurer.annotations.Conf;
import reka.core.bundle.UseConfigurer;
import reka.core.bundle.UseInit;
import reka.core.config.ConfigurerProvider;
import reka.core.config.SequenceConfigurer;
import reka.core.util.StringWithVars;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class UseBuiltins extends UseConfigurer {
	
	private static final Logger log = LoggerFactory.getLogger(UseBuiltins.class);

	@Override
	public void setup(UseInit init) {
		
		init.operation(asList("set", "put", "+"), () -> new PutConfigurer());
		init.operation(asList("putvars", "putv"), () -> new PutWithVarsConfigurer());
		init.operation(asList("copy", "cp"), () -> new CopyConfigurer());
    	init.operation(asList("run", "then"), (provider) -> new RunConfigurer(provider));
    	init.operation("run/parallel", (provider) -> new RunParallelConfigurer(provider));
    	init.operation("log", () -> new LogConfigurer());
    	init.operation("label", (provider) -> new LabelConfigurer(provider));
    	init.operation("stringwithvariables", () -> new StringWithVariablesConfigurer());
    	init.operation("sleep", () -> new SleepConfigurer());
    	init.operation("halt!", () -> new HaltConfigurer());
    	init.operation("uuid/generate", () -> new GenerateUUIDConfigurer());
    	init.operation("println", () -> new PrintlnConfigurer());
    	init.operation("bcrypt/hashpw", () -> new BCryptHashpwConfigurer());
    	init.operation("bcrypt/checkpw", (provider) -> new BCryptCheckpwConfigurer(provider));
    	init.operation("throw", () -> new ThrowConfigurer());
    	init.operation("inspect", () -> new InspectConfigurer());
    	init.operation("random/string", () -> new RandomStringConfigurer());
    	init.operation("coerce", () -> new Coercion.CoerceConfigurer());
    	init.operation("coerce/int64", () -> new Coercion.CoerceLongConfigurer());
    	init.operation("coerce/bool", () -> new Coercion.CoerceBooleanConfigurer());
    	
		init.trigger(Path.path("every"), () -> new TimerExport());
		
	}
	
	public static class BCryptCheckpwConfigurer implements Supplier<FlowSegment> {

		private final ConfigurerProvider provider;
		
		private Path readPwFrom = dots("bcrypt.pw");
		private Path readHashFrom = dots("bcrypt.hash");
		
		private Supplier<FlowSegment> ok;
		private Supplier<FlowSegment> fail;
		
		public BCryptCheckpwConfigurer(ConfigurerProvider provider) {
			this.provider = provider;
		}

		@Conf.At("read-pw-from")
		public void readPwFrom(String val) {
			readPwFrom = dots(val);
		}
		
		@Conf.At("read-hash-from")
		public void readHashFrom(String val) {
			readHashFrom = dots(val);
		}
		
		@Conf.At("ok")
		public void ok(Config config) {
			ok = configure(new SequenceConfigurer(provider), config);
		}
		
		@Conf.At("fail")
		public void fail(Config config) {
			fail = configure(new SequenceConfigurer(provider), config);
		}
		
		@Override
		public FlowSegment get() {
			
			return sequential(seq -> {
				seq.routerNode("bcrypt/checkpw", (data) -> new BCryptCheckpwOperation(readPwFrom, readHashFrom));
				seq.parallel(par -> {
					par.add("ok", ok.get());
					par.add("fail", fail.get());
				});
			});
			
		}
		
	}
	
	public static class BCryptCheckpwOperation implements RoutingOperation {

		private final Path readPwFrom;
		private final Path readHashFrom;

		public BCryptCheckpwOperation(Path readPwFrom, Path readHashFrom) {
			this.readPwFrom = readPwFrom;
			this.readHashFrom = readHashFrom;
		}
		
		@Override
		public MutableData call(MutableData data, RouteCollector router) {
			
			router.defaultRoute("fail");
			
			data.getContent(readPwFrom).ifPresent(pw -> {
				data.getContent(readHashFrom).ifPresent(hash -> {
					router.routeTo("ok");
				});
			});
			
			return data;
		}
		
	}
	
	public static class BCryptHashpwConfigurer implements Supplier<FlowSegment> {

		private Path in = dots("bcrypt.pw");
		private Path out = dots("bcrypt.hash");
		
		@Conf.At("in")
		public void in(String val) {
			in = dots(val);
		}
		
		@Conf.At("out")
		public void out(String val) {
			out = dots(val);
		}
		
		@Override
		public FlowSegment get() {
			return sync("bcrypt/hashpw", () -> new BCryptHashpwOperation(in, out));
		}
		
	}
	
	public static class BCryptHashpwOperation implements SyncOperation {

		private final Path in;
		private final Path out;

		public BCryptHashpwOperation(Path in, Path out) {
			this.in = in;
			this.out = out;
		}
		
		@Override
		public MutableData call(MutableData data) {
			data.getContent(in).ifPresent(content -> {
				data.putString(out, BCrypt.hashpw(content.asUTF8(), BCrypt.gensalt()));
			});
			return data;
		}
		
	}
	
	private static final Random RANDOM = new Random();
	
	public static class InspectConfigurer implements Supplier<FlowSegment> {

		@Override
		public FlowSegment get() {
			return sync("inspect", () -> new InspectOperation());
		}
		
	}
	
	public static class InspectOperation implements SyncOperation {

		@Override
		public MutableData call(MutableData data) {
			data.forEachContent((path, content) -> {
				log.debug("{} ->|{}|<- ({})", path.dots(), content, content.getClass());
			});
			return data;
		}
		
	}
	
	public static class RandomStringConfigurer implements Supplier<FlowSegment> { 

		private static final char[] chars;
		
		static {
			chars = "abcdefghijklmnopqrstuvwzyzABCDEFGHIJKLMNOPQRSTUVQXYZ0123456789".toCharArray();
		}
		
		private int length = 12;
		private Path out = Path.Response.CONTENT;
		
		@Conf.Val
		@Conf.At("length")
		public void length(String val) {
			length = Integer.valueOf(val);
		}
		
		@Conf.At("out")
		public void out(String val) {
			out = dots(val);
		}
		
		@Override
		public FlowSegment get() {
			return sync("random/string", () -> new RandomStringOperation(length, chars, RANDOM, out));
		}
		
	}
	
	public static class RandomStringOperation implements SyncOperation {

		private final int length;
		private final char[] chars;
		private final Random random;
		private final Path out;
		
		public RandomStringOperation(int length, char[] chars, Random random, Path out) {
			this.length = length;
			this.chars = chars;
			this.random = random;
			this.out = out;
		}
		
		@Override
		public MutableData call(MutableData data) {
			char[] buf = new char[length];
			
			for (int i = 0; i < length; i++) {
				buf[i] = chars[random.nextInt(chars.length)];
			}
			
			return data.putString(out, new String(buf));
		}
		
	}
	
	public static class ThrowConfigurer implements Supplier<FlowSegment> {
		
		private Function<Data,String> msgFn = (data) -> "error";
		
		@Conf.Val
		public void msg(String val) {
			msgFn = StringWithVars.compile(val);
		}

		@Override
		public FlowSegment get() {
			return sync("throw", () -> new ThrowOperation(msgFn));
		}
		
	}
	
	public static class PrintlnConfigurer implements Supplier<FlowSegment> {

		private Function<Data,String> msg;
		
		@Conf.Val
		public void msg(String val) {
			msg = StringWithVars.compile(val);
		}
		
		@Override
		public FlowSegment get() {
			return sync("println", () -> new PrintlnOperation(msg));
		}
		
	}
	
	public static class PrintlnOperation implements SyncOperation {

		private final Function<Data,String> msg;
		
		public PrintlnOperation(Function<Data,String> msg) {
			this.msg = msg;
		}
		
		@Override
		public MutableData call(MutableData data) {
			System.out.println(msg.apply(data));
			return data;
		}
		
	}
	
	public static class GenerateUUIDConfigurer implements Supplier<FlowSegment>, ErrorReporter {

		private Path out = dots("uuid");
		
		@Conf.Val
		public void out(String val) {
			if (val.startsWith(":")) val = val.substring(1);
			out = dots(val);
		}

		@Override
		public void errors(ErrorCollector errors) {
			errors.checkConfigPresent(out, "please specify a value to tell us where to write the uuid, e.g. :params.uuid");
		}
		
		@Override
		public FlowSegment get() {
			return sync("uuid/generate", () -> new GenerateUUIDOperation(out));
		}
		
	}
	
	public static class GenerateUUIDOperation implements SyncOperation {

		private final Path out;
		
		public GenerateUUIDOperation(Path out) {
			this.out = out;
		}
		
		@Override
		public MutableData call(MutableData data) {
			return data.putString(out, UUID.randomUUID().toString());
		}
		
	}
	
	public static class SleepConfigurer implements Supplier<FlowSegment> {

		private long ms = 1000L;
		
		@Conf.Val
		public void timeout(String val) {
			ms = Long.valueOf(val);
		}
		
		@Override
		public FlowSegment get() {
			return async("sleep", () -> new SleepOperation(ms));
		}
		
	}
	
	public static class SleepOperation implements AsyncOperation {

		
		private static final ScheduledExecutorService e = Executors.newScheduledThreadPool(1);
		
		private final long ms;
		
		public SleepOperation(long ms) {
			this.ms = ms;
		}

		@Override
		public ListenableFuture<MutableData> call(MutableData data) {
			SettableFuture<MutableData> future = SettableFuture.create();
			
			e.schedule(new Runnable(){ 
				@Override public void run() { future.set(data); } 
			}, ms, TimeUnit.MILLISECONDS);
			
			return future;
		}
	}
	
	public static class RunParallelConfigurer implements Supplier<FlowSegment> {
		
		private final ConfigurerProvider provider;
		
		private final List<Supplier<FlowSegment>> items = new ArrayList<>();
		
		public RunParallelConfigurer(ConfigurerProvider provider) {
			this.provider = provider;
		}
		
		@Conf.Each
		public void item(Config config) {
			log.debug("configuring parallel: {}", config.key());
			items.add(configure(new SequenceConfigurer(provider), ConfigBody.of(config.source(), config)));
		}

		@Override
		public FlowSegment get() {
			List<FlowSegment> segments = items.stream().map(Supplier<FlowSegment>::get).collect(toList());
			return par(segments);
		}
		
	}
	
	public static class HaltConfigurer implements Supplier<FlowSegment> {

		@Override
		public FlowSegment get() {
			return halt();
		}
		
	}
	
	public static class StringWithVariablesConfigurer implements Supplier<FlowSegment> {

		private Function<Data,String> template;
		private Path out = Response.CONTENT;
		
		@Conf.Config
		public void config(Config config) {
			if (config.hasValue()) {
				template = StringWithVars.compile(config.valueAsString());
			} else if (config.hasDocument()) {
				template = StringWithVars.compile(config.documentContentAsString());
				if (config.hasValue()) {
					out = dots(config.valueAsString());
				}
			}
		}
		
		@Override
		public FlowSegment get() {
			return sync("stringwithvariables", (data) -> new DataContentFunctionOperation(template, out));
		}
		
	}
	
	public static class DataContentFunctionOperation implements SyncOperation {
		
		private final Function<Data,String> template;
		private final Path out;
		
		public DataContentFunctionOperation(Function<Data,String> template, Path out) {
			this.template = template;
			this.out = out;
		}

		@Override
		public MutableData call(MutableData data) {
			return data.putString(out, template.apply(data));
		}
		
	}
	
	public static class LabelConfigurer implements Supplier<FlowSegment> {

		private final ConfigurerProvider provider;
		private String name;
		private Supplier<FlowSegment> configurer;
		
		public LabelConfigurer(ConfigurerProvider provider) {
			this.provider = provider;
		}
		
		@Conf.Config
		public void config(Config config) {
			if (config.hasValue()) {
				name = config.valueAsString();
			}
			configurer = configure(new SequenceConfigurer(provider), config);
		}
		
		@Override
		public FlowSegment get() {
			return label(name, configurer.get());
		}
		
	}
	
	public static class RunConfigurer implements Supplier<FlowSegment> {
		
		private final ConfigurerProvider provider;
		private Supplier<FlowSegment> configurer;
		
		public RunConfigurer(ConfigurerProvider provider) {
			this.provider = provider;
		}

		@Conf.Val
		public void embed(String val) {
			configurer = new EmbeddedFlowConfigurer().name(val);
		}
		
		@Conf.Config
		public void config(Config config) {
			if (config.hasBody()) {
				configurer = configure(new SequenceConfigurer(provider), config);
			}
		}
		
		@Override
		public FlowSegment get() {
			return configurer.get();
		}
		
	}
	
	public static class LogConfigurer implements Supplier<FlowSegment> {

		private Path in;
		
		@Conf.Val
		public void in(String val) {
			in = dots(val);
		}
		
		@Override
		public FlowSegment get() {
			return sync("log", () -> new LogOperation(in));
		}
		
	}
	
	public static class LogOperation implements SyncOperation {
		
		private final Path in;
		
		public LogOperation(Path in) {
			this.in = in;
		}

		@Override
		public MutableData call(MutableData data) {
			log.debug(data.at(in).toPrettyJson());
			return data;
		}
		
	}
	
	public static class CopyConfigurer implements Supplier<FlowSegment> {
		
		private final ImmutableList.Builder<Entry<Path,Path>> entries = ImmutableList.builder();

		@Conf.Each
		public void entry(Config item) {
			//if (config.hasBody()) {
				//for (Config item : config.body()) {
					if (item.hasValue()) {
						log.debug("adding copy [{}] -> [{}]", dots(item.key()).dots(), dots(item.valueAsString()).dots());
						entries.add(createEntry(dots(item.key()), dots(item.valueAsString())));
					}
				//}
			//}
		}
		
		@Override
		public FlowSegment get() {
			return sync("copy", () -> new CopyOperation(entries.build()));
		}
		
	}
	
	public static class CopyOperation implements SyncOperation {

		private final List<Entry<Path,Path>> entries;
		
		public CopyOperation(List<Entry<Path,Path>> entries) {
			this.entries = entries;
		}
		
		@Override
		public MutableData call(MutableData data) {
			for (Entry<Path,Path> e : entries) {
				Data d = data.at(e.getKey());
				
				if (!d.isPresent()) continue;
				
				if (d.isContent()) {
					data.put(e.getValue(), d.content());
				} else {
					Path base = e.getValue();
					d.forEachContent((p, c) -> {
						data.put(base.add(p), c);
					});
				}
				
			}
			return data;
		}
		
	}
	
	public static class PutConfigurer implements Supplier<FlowSegment> {

		private Content content;
		private Data data;
		private Path out = root();
		
		@Conf.Config
		public void config(Config config) {
			if (config.hasDocument()) {
				content = binary(config.documentType(), config.documentContent());
			} else if (config.hasBody()) {
				data = configToData(config.body());
			}
		}

		@Conf.Val
		@Conf.At("out")
		public void out(String val) {
			out = dots(val);
		}
		
		@Override
		public FlowSegment get() {
			if (content != null) {
				return sync("put", () -> new PutContentOperation(content, out));
			} else if (data != null) {
				return sync("put", () -> new PutDataOperation(data, out));
			}
			throw runtime("you must have content or data");
		}
		
	}
		
	public static class PutWithVarsConfigurer implements Supplier<FlowSegment> {

		private Data data;
		private Path out = root();
		
		@Conf.Config
		public void config(Config config) {
			checkConfig(config.hasBody(), "must have body!");
			data = configToData(config.body());
			// TODO: this is where we need to go through te data and find out which bit has vars in it...
		}

		@Conf.Val
		@Conf.At("out")
		public void out(String val) {
			out = dots(val);
		}
		
		@Override
		public FlowSegment get() {
			if (data != null) {
				return sync("putvars", () -> new PutDataWithVarsOperation(data, out));
			}
			throw runtime("you must have content or data");
		}
		
	}
	
	public static class PutContentOperation implements SyncOperation {

		private final Content content;
		private final Path out;
		
		public PutContentOperation(Content content, Path out) {
			this.content = content;
			this.out = out;
		}
		
		@Override
		public MutableData call(MutableData data) {
			return data.put(out, content);
		}
		
	}
	
	public static class PutDataOperation implements SyncOperation {

		private final Data dataValue;
		private final Path out;
		
		public PutDataOperation(Data data, Path out) {
			this.dataValue = data;
			this.out = out;
		}
		
		@Override
		public MutableData call(MutableData data) {
			dataValue.forEachContent((path, content) -> {
				data.put(out.add(path), content);
			});
			return data;
		}
		
	}
	
	public static class PutDataWithVarsOperation implements SyncOperation {

		private final LoadingCache<Content,StringWithVars> cache = CacheBuilder.newBuilder()
				.build(new CacheLoader<Content, StringWithVars>(){

					@Override
					public StringWithVars load(Content content) throws Exception {
						return StringWithVars.compile(content.asUTF8());
					}
					
				});
		
		private final Data dataValue;
		private final Path out;
		
		public PutDataWithVarsOperation(Data data, Path out) {
			this.dataValue = data;
			this.out = out;
		}
		
		@Override
		public MutableData call(MutableData data) {
			// TODO: fix this up, do it BEFORE runtime as much as we can
			dataValue.forEachContent((path, content) -> {
				try {
					data.putString(out.add(path), cache.get(content).apply(data));
				} catch (Exception e) {
					throw unchecked(e);
				}
			});
			return data;
		}
		
	}

}
