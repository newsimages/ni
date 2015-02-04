package news.search;

import java.io.IOException;
import java.io.InputStream;

public abstract class SearchEngine {

	public static class Result {
		private InputStream nzb;
		private boolean moreAvailable;

		Result(InputStream nzb, boolean moreAvailable) {
			this.nzb = nzb;
			this.moreAvailable = moreAvailable;
		}

		public InputStream getNzb() {
			return nzb;
		}

		public boolean isMoreAvailable() {
			return moreAvailable;
		}
	}

	public abstract Result search(String pattern, String filter, int max,
			int age, int offset) throws IOException;

	public static SearchEngine get(String name) throws IOException {
		try {
			return (SearchEngine) Class.forName(
					"news.search." + name).newInstance();
		} catch (Exception ex) {
			throw new IOException("Search engine '" + name + "' is unknown");
		}
	}
}
