package news;

import java.util.Comparator;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import alphanum.AlphanumComparator;

@XmlRootElement
public class Attachment implements Comparable<Attachment> {
	
	@XmlAttribute public String filename;
	@XmlTransient public byte[] data;
	
	@SuppressWarnings("unchecked")
	private static Comparator<String> alphanum = new AlphanumComparator();
	
	public Attachment() {}
	
	public Attachment(String filename, byte[] data) {
		this.filename = filename;
		this.data = data;
	}
	
	@Override
	public int compareTo(Attachment o) {
		return alphanum.compare(this.filename, o.filename);
	}
}
