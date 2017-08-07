package news.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import news.ArticleBody;
import news.Attachment;

public class DiskCache {

	private int maxSize;
	
	@XmlRootElement
	public static class IndexEntry {
		@XmlAttribute public String articleId;
		@XmlAttribute public String fileName;
		@XmlAttribute public int size;
	}
	
	@XmlRootElement
	public static class Index {
		@XmlAttribute public int size;
		@XmlElement public ArrayList<IndexEntry> entries;
	}
	
	private Index index;
	private HashMap<String, String> map = new HashMap<String, String>();
	
	public DiskCache(int maxSize){
		this.maxSize = maxSize;
		//System.out.println("Init disk cache...");
		index = (Index)readObject("index", Index.class);
		if(index == null){
			index = new Index();
			index.entries = new ArrayList<DiskCache.IndexEntry>();
		} else {
			for(IndexEntry entry : index.entries){
				map.put(entry.articleId, entry.fileName);
			}
		}
		//System.out.println("  -> ok, cache size = " + index.size + " bytes");
	}
	
	public DiskCache(){
		this(100*1024*1024);
	}
	
	public void put(String articleId, ArticleBody body){
		//System.out.println("Putting article in cache: " + articleId + " (" + body.size + " bytes)");
		while(index.size + body.size > maxSize && index.entries.size() > 0){
			IndexEntry oldestEntry = index.entries.remove(0);
			//System.out.println("Cache size limit exceeded, removing oldest entry: " + oldestEntry.articleId + "(" + oldestEntry.size + "bytes)");
			ArticleBody oldBody = get(oldestEntry.articleId);
			map.remove(oldestEntry.articleId);
			index.size -= oldestEntry.size;
			//System.out.println(" remove body file " + getFilePath(oldestEntry.fileName));
			new File(getFilePath(oldestEntry.fileName)).delete();
			if(oldBody != null){
				 ArrayList<Attachment> atts = oldBody.attachments;
				 if(atts != null){
					 for(int i = 0; i < atts.size(); i++){
							//System.out.println(" remove atatchment file " + getFilePath(oldestEntry.fileName + "_" + i));
						new File(getFilePath(oldestEntry.fileName + "_" + i)).delete();
					 }
				 }
			}
		}
		if(index.size + body.size <= maxSize){
			String fileName = String.valueOf(System.currentTimeMillis());
			IndexEntry entry = new IndexEntry();
			entry.articleId = articleId;
			entry.fileName = fileName;
			entry.size = body.size;
			index.entries.add(entry);
			map.put(articleId, fileName);
			index.size += body.size;
			writeObject(body, fileName);
		}
		writeObject(index, "index");
		//System.out.println("  -> ok, new cache size = " + index.size + " bytes");
	}
	
	public ArticleBody get(String articleId){
		//System.out.println("Getting article from cache: " + articleId);
		String fileName = map.get(articleId);
		ArticleBody body = null;
		if(fileName != null){
			body = (ArticleBody)readObject(fileName, ArticleBody.class);
		}
		if(body != null){
			//System.out.println("  -> hit: " + articleId + " (" + body.size + " bytes)");
		} else {
			//System.out.println("  -> miss");
		}
		return body;
	}
	
	public CacheInfo getInfo(){
		CacheInfo info = new CacheInfo();
		info.maxSize = maxSize;
		info.size = index.size;
		info.count = index.entries.size();
		return info;
	}
	
	public void clear(){
		//System.out.println("Clearing cache...");
		File dir = new File(getCacheDir());
		for(File file: dir.listFiles()){ 
		    if (!file.isDirectory()){
				//System.out.println("    delete " + file.getName());
		        file.delete();
		    }
		}
		index.size = 0;
		index.entries.clear();
		map.clear();
		//System.out.println("  -> cache cleared.");
	}
	
	private String getCacheDir() {
		String dir = System.getenv("OPENSHIFT_DATA_DIR");
		if(dir == null){
			dir = "data";
		}
		dir += File.separator  + ".ni" + File.separator + "cache";
		return dir;
	}
	
	private String getFilePath(String filename){
		String dir = getCacheDir();
		File dirFile = new File(dir);
		if(!dirFile.exists()){
			dirFile.mkdirs();
		}
		return dir + File.separator + filename;
	}

	private Object readObject(String filename, @SuppressWarnings("rawtypes") Class t){
		try {
			JAXBContext jc = JAXBContext.newInstance(t);
			Unmarshaller um = jc.createUnmarshaller();
			String path = getFilePath(filename);
			//System.out.println("    (reading object from file " + path + "...)");
			InputStream in = new BufferedInputStream(new FileInputStream(path));
			Object obj = um.unmarshal(in);
			//System.out.println("    (object read: " + obj + ")");
			in.close();
			if(obj instanceof ArticleBody){
				 ArrayList<Attachment> atts = ((ArticleBody)obj).attachments;
				 if(atts != null){
					 for(int i = 0; i < atts.size(); i++){
						 Attachment att = atts.get(i);
						 if(att != null){
							 path = getFilePath(filename + "_" + i);
							 try {
								 //System.out.println("    (reading attachment from file " + path + "...)");
								 att.data = Files.readAllBytes(FileSystems.getDefault().getPath(path));
								 //System.out.println("    (attachment read: " + att.data.length + " bytes)");
							 } catch(Exception ex){
							 }
						 }
					 }
				 }
			}
			return obj;
		} catch (Exception e) {
			//System.out.println("    (###  " + e + ")");
			return null;
		}
	}
	
	private void writeObject(Object obj, String filename){
		try {
			JAXBContext jc = JAXBContext.newInstance(obj.getClass());
			Marshaller m = jc.createMarshaller();
			String path = getFilePath(filename);
			//System.out.println("    (writing object " + obj + " to file " + path + "...)");
			OutputStream out = new BufferedOutputStream(new FileOutputStream(new File(path).getAbsolutePath()));
			m.marshal(obj, out);
			out.flush();
			out.close();
			if(obj instanceof ArticleBody){
				 ArrayList<Attachment> atts = ((ArticleBody)obj).attachments;
				 if(atts != null){
					 for(int i = 0; i < atts.size(); i++){
						 Attachment att = atts.get(i);
						 if(att != null && att.data != null){
							 path = getFilePath(filename + "_" + i);
							 //System.out.println("    (writing attachment to file " + path + "...)");
							 out = new BufferedOutputStream(new FileOutputStream(new File(path).getAbsolutePath()));
							 out.write(att.data);
							 out.flush();
							 out.close();
						 }
					 }
				 }
			}
		} catch (Exception e) {
			//System.out.println("    (###  " + e + ")");
		}
	}
}
