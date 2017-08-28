package news.cache;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class CacheInfo {
	@XmlAttribute public long maxSize;
	@XmlAttribute public long size;
	@XmlAttribute public int count;
}
