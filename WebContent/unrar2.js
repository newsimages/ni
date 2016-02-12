define(["dojo/Deferred", "libunrar.js", "rpc.js"], function(Deferred) {
	
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

	function dir(d, attachments){
		_dir(d, attachments);
		attachments.sort(function(a1, a2) { return a1.filename.localeCompare(a2.filename); });
		return attachments;
	}
	
	var worker = false;
	
	var unrar = function(filename, data, sync) {

		var deferred = new Deferred();

		var args = [{ name: filename, content: data }];
		
		if(sync){
			var d = readRARContent(args);
			deferred.resolve(dir(d, []));
		} else if(worker){
			var sizeProcessed = 0;
			var totalSize = data.length;
			var so = {
					loaded:function(){},
					progressShow:function(fileName, fileSize, progress){
						sizeProcessed += fileSize;
						deferred.progress({
									file : fileName,
									index : sizeProcessed,
									total : totalSize
								});
					}
			};
			RPC.new("./unrar-worker.js", so).then(function(rpc) {
				rpc.transferables = [data.buffer];
				rpc.unrar(args).then(function(d){
					deferred.resolve(dir(d, []));
				});
			});
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
