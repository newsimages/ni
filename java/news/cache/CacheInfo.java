package news.cache;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class CacheInfo {
	@XmlAttribute public int maxSize;
	@XmlAttribute public int size;
	@XmlAttribute public int count;
}
