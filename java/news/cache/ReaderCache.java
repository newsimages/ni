package news.cache;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ReaderCache {

	private long maxSize = 500*1024*1024;
	
	private File cacheDir;
	private long currentSize;
	
	public ReaderCache() {
		
		String dir = System.getenv("OPENSHIFT_DATA_DIR");
		if(dir == null){
			dir = "data";
		}
		dir += File.separator  + ".ni" + File.separator + "cache";
		cacheDir = new File(dir);
		
		if(!cacheDir.exists()){
			cacheDir.mkdirs();
		}

		File[] files = cacheDir.listFiles();
		for(int i = 0; i < files.length; i++){
			currentSize += files[i].length();
		}
		
		//System.out.println("Cache size = " + currentSize + " (" + files.length + " articles), max = " + maxSize);
	}
	
	private File getFile(String id) {
		return new File(cacheDir, id.replaceAll("[\\/:*?\"<>|]", "_"));
	}
	
	public BufferedReader get(String id) throws FileNotFoundException {
		File file = getFile(id);
		if(file.exists()){
			//System.out.println(id + " found in cache (" + file.length() + " bytes)");
			return new BufferedReader(new FileReader(file));
		}
		//System.out.println(id + " not found in cache.");
		return null;
	}
	
	public BufferedReader put(String id, BufferedReader reader) throws IOException {
		
		final File file = getFile(id);
		
		return new BufferedReader(reader){
			
			BufferedWriter writer = new BufferedWriter(new FileWriter(file));
			
		    public int read(char cbuf[], int off, int len) throws IOException {
		    	int n = super.read(cbuf, off, len);
		    	if(n == -1){
		    		return -1;
		    	}
		    	makeRoom(n);
				if(writer != null){
					writer.write(cbuf, off, n);
					currentSize += n;
				}
				return n;
			}
		    
		    public String readLine() throws IOException {
		    	String line = super.readLine();
		    	if(line == null){
		    		return null;
		    	}
		    	int len = line.length() + System.lineSeparator().length();
		    	makeRoom(len);
				if(writer != null){
					writer.write(line);
					writer.newLine();
					currentSize += len;
				}
				return line;
			}
		    
		    private void makeRoom(int len) throws IOException {
				while(currentSize + len > maxSize){
					//System.out.println("*** max cache size reached:");
					// delete oldest file
					File[] files = cacheDir.listFiles();
					long oldestTime = Long.MAX_VALUE;
					File oldestFile = null;
					for(int i = 0; i < files.length; i++){
						File file = files[i];
						if(file.lastModified() < oldestTime){
							oldestFile = file;
							oldestTime = file.lastModified();
						}
					}
					//System.out.println("    deleting " + oldestFile.getName() + "...");
					if(oldestFile.getName().equals(file.getName())){
						// we have to delete the file we are currently writing to...
						writer.close();
						writer = null;
					}
					long size = oldestFile.length();
					if(oldestFile.delete()){
						currentSize -= size;
						//System.out.println("    cache size is now " + currentSize);
					}
				}
		    }

			public void close() throws IOException {
				super.close();
				writer.flush();
				writer.close();
				//System.out.println(file.getName() + ": " + file.length() + " bytes saved, current cache size = " + currentSize);
			}
		};
}
	
	public CacheInfo getInfo() {
		CacheInfo info = new CacheInfo();
		info.count = cacheDir.listFiles().length;
		info.maxSize = maxSize;
		info.size = currentSize;
		return info;
	}
	
	public void remove(String id) {
		getFile(id).delete();
	}
	
	public void clear() {
		File[] files = cacheDir.listFiles();
		for(int i = 0; i < files.length; i++){
			files[i].delete();
		}
		currentSize = 0;
	}
}
