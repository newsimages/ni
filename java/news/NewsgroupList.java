package news;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class NewsgroupList {
	@XmlElement public List<Newsgroup> newsgroups = new ArrayList<Newsgroup>();
	@XmlAttribute long total;
}
