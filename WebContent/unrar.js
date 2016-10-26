define(["dojo/Deferred", "libunrar.js"], function(Deferred, readRARContent) {
	
	function _dir(d, attachments){
		for(var name in d.ls){
			var f = d.ls[name];
			if(f.type === "file"){
				attachments.push({
					filename: name,
					data: f.fileContent
				});
			} else if(f.type === "dir"){
				_dir(f, attachments);
			}
		}
		return attachments;
	}

	/* ********************************************************************
	 * Alphanum sort() function version - case sensitive
	 *  - Slower, but easier to modify for arrays of objects which contain
	 *    string properties
	 *
	 */
	function alphanum(a, b) {
	  function chunkify(t) {
	    var tz = new Array();
	    var x = 0, y = -1, n = 0, i, j;

	    while (i = (j = t.charAt(x++)).charCodeAt(0)) {
	      var m = (i == 46 || (i >=48 && i <= 57));
	      if (m !== n) {
	        tz[++y] = "";
	        n = m;
	      }
	      tz[y] += j;
	    }
	    return tz;
	  }

	  var aa = chunkify(a);
	  var bb = chunkify(b);

	  for (var x = 0; aa[x] && bb[x]; x++) {
	    if (aa[x] !== bb[x]) {
	      var c = Number(aa[x]), d = Number(bb[x]);
	      if (c == aa[x] && d == bb[x]) {
	        return c - d;
	      } else return (aa[x] > bb[x]) ? 1 : -1;
	    }
	  }
	  return aa.length - bb.length;
	}
	
	function dir(d, attachments){
		_dir(d, attachments);
		attachments.sort(function(a1, a2) { return alphanum(a1.filename, a2.filename); });
		return attachments;
	}
	
	var unrar = function(filename, data, sync) {

		var deferred = new Deferred();

		var args = [{ name: filename, content: data }];
		
		if(sync){
			var d = readRARContent(args);
			deferred.resolve(dir(d, []));
		} else {
			setTimeout(function(){
				var d = readRARContent(args);
				deferred.resolve(dir(d, []));
			});
		}
			
		return deferred;
	};
	
	return unrar;
});
