package news;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class ArticleList {
	@XmlElement public List<ArticleHeader> articles = new ArrayList<ArticleHeader>();
	@XmlAttribute public long available;
	@XmlAttribute public long offset;
}
