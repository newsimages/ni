package news;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class HeaderGroupMatcher {
	
	private static HeaderGroupMatcher multipartMatcher = new MultipartMatcher();
	private static HeaderGroupMatcher[] multiVolumeMatchers = new HeaderGroupMatcher[] {
			new MultivolumeMatcher(".*\"(.*)\\.part(\\d+)\\.rar\".*", false),
			new MultivolumeMatcher("(.*\\.\\w+\\.)(\\d\\d\\d)[\"\\s].*", false),
			new MultivolumeMatcher("(.*\\.r)(\\d\\d)[\"\\s].*", true)
	};
	
	public static HeaderGroupMatchResult matchMultipart(String subject) {
		return multipartMatcher.match(subject);
	}
	
	public static HeaderGroupMatchResult matchMultivolume(String subject) {
		for(int i = 0; i < multiVolumeMatchers.length; i++){
			HeaderGroupMatchResult r = multiVolumeMatchers[i].match(subject);
			if(r != null){
				return r;
			}
		}
		return null;
	}
	
	public static class HeaderGroupMatchResult {
		private int partCount;
		private int partNumber;
		private String key;
		private String prefix;
		private String suffix;

		private HeaderGroupMatchResult(int partCount, int partNumber, String key, String prefix,
				String suffix) {
			this.partCount = partCount;
			this.partNumber = partNumber;
			this.key = key;
			this.prefix = prefix;
			this.suffix = suffix;
		}

		private HeaderGroupMatchResult(int partCount, int partNumber, String key) {
			this(partCount, partNumber, key, null, null);
		}

		public int getPartCount() {
			return partCount;
		}
		
		public int getPartNumber() {
			return partNumber;
		}
		
		public String getKey() {
			return key;
		}

		public String getPrefix() {
			return prefix;
		}

		public String getSuffix() {
			return suffix;
		}
	}
	
	protected abstract HeaderGroupMatchResult match(String subject);
	
	private static abstract class RegexHeaderGroupMatcher extends HeaderGroupMatcher {
		private Pattern pattern;
		
		protected RegexHeaderGroupMatcher(String regex) {
			pattern = Pattern.compile(regex);
		}
		
		protected HeaderGroupMatchResult match(String subject) {
			Matcher m = pattern.matcher(subject);
			if(m.matches()){
				return getMatchResult(m);
			}
			return null;
		}

		protected abstract HeaderGroupMatchResult getMatchResult(Matcher m);
	}
	
	private static class MultipartMatcher extends RegexHeaderGroupMatcher {
		
		private MultipartMatcher() {
			super("(.*)\\((\\d+)/(\\d+)\\)(.*)");
		}

		protected HeaderGroupMatchResult getMatchResult(Matcher m) {
			if (m.groupCount() == 4) {
				int partNumber = Integer.parseInt(m.group(2));
				int partCount = Integer.parseInt(m.group(3));

				if (partCount != 1 && partNumber != 0) {

					String prefix = m.group(1);
					String suffix = m.group(4);

					String key = prefix + "X/" + partCount + suffix;
					
					return new HeaderGroupMatchResult(partCount, partNumber, key, prefix, suffix);
				}
			}
			return null;
		}
	}

	private static class MultivolumeMatcher extends RegexHeaderGroupMatcher {
		
		private boolean zeroBased;
		
		private MultivolumeMatcher(String regex, boolean zeroBased) {
			super(regex);
			this.zeroBased = zeroBased;
		}
		
		protected HeaderGroupMatchResult getMatchResult(Matcher m) {
			if (m.groupCount() == 2) {

				String key = m.group(1);
				String n = m.group(2);
				int partNumber = Integer.parseInt(n);
				if(zeroBased){
					partNumber++;
				}
				int partCount = (int) Math.pow(10, n.length());
				
				return new HeaderGroupMatchResult(partCount, partNumber, key);
			}
			return null;
		}
	}
}
