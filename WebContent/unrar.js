define(
		[ "dojo/Deferred" ],
		function(Deferred) {

			// Binary I/O helpers

			var bitjs = {};
			bitjs.io = bitjs.io || {};

			// mask for getting the Nth bit (zero-based)
			bitjs.BIT = [ 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80,
					0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000, 0x8000 ];

			// mask for getting N number of bits (0-8)
			var BITMASK = [ 0, 0x01, 0x03, 0x07, 0x0F, 0x1F, 0x3F, 0x7F, 0xFF ];

			/**
			 * This bit stream peeks and consumes bits out of a binary stream.
			 * 
			 * {ArrayBuffer} ab An ArrayBuffer object or a Uint8Array. {boolean}
			 * rtl Whether the stream reads bits from the byte starting from bit
			 * 7 to 0 (true) or bit 0 to 7 (false). {Number} opt_offset The
			 * offset into the ArrayBuffer {Number} opt_length The length of
			 * this BitStream
			 */
			bitjs.io.BitStream = function(ab, rtl, opt_offset, opt_length) {
				if (!ab || !ab.toString
						|| ab.toString() !== "[object ArrayBuffer]") {
					throw "Error! BitArray constructed with an invalid ArrayBuffer object";
				}

				var offset = opt_offset || 0;
				var length = opt_length || ab.byteLength;
				this.bytes = new Uint8Array(ab, offset, length);
				this.bytePtr = 0; // tracks which byte we are on
				this.bitPtr = 0; // tracks which bit we are on (can have
				// values 0 through 7)
				this.peekBits = rtl ? this.peekBits_rtl : this.peekBits_ltr;
			};

			// byte0 byte1 byte2 byte3
			// 7......0 | 7......0 | 7......0 | 7......0
			// 
			// The bit pointer starts at bit0 of byte0 and moves left until it
			// reaches
			// bit7 of byte0, then jumps to bit0 of byte1, etc.
			bitjs.io.BitStream.prototype.peekBits_ltr = function(n,
					movePointers) {
				if (n <= 0 || typeof n != typeof 1) {
					return 0;
				}

				var movePointers = movePointers || false, bytePtr = this.bytePtr, bitPtr = this.bitPtr, result = 0, bitsIn = 0, bytes = this.bytes;

				// keep going until we have no more bits left to peek at
				// TODO: Consider putting all bits from bytes we will need into
				// a variable and then
				// shifting/masking it to just extract the bits we want.
				// This could be considerably faster when reading more than 3 or
				// 4 bits at a time.
				while (n > 0) {
					if (bytePtr >= bytes.length) {
						throw "Error!  Overflowed the bit stream! n=" + n
								+ ", bytePtr=" + bytePtr + ", bytes.length="
								+ bytes.length + ", bitPtr=" + bitPtr;
						return -1;
					}

					var numBitsLeftInThisByte = (8 - bitPtr);
					if (n >= numBitsLeftInThisByte) {
						var mask = (BITMASK[numBitsLeftInThisByte] << bitPtr);
						result |= (((bytes[bytePtr] & mask) >> bitPtr) << bitsIn);

						bytePtr++;
						bitPtr = 0;
						bitsIn += numBitsLeftInThisByte;
						n -= numBitsLeftInThisByte;
					} else {
						var mask = (BITMASK[n] << bitPtr);
						result |= (((bytes[bytePtr] & mask) >> bitPtr) << bitsIn);

						bitPtr += n;
						bitsIn += n;
						n = 0;
					}
				}

				if (movePointers) {
					this.bitPtr = bitPtr;
					this.bytePtr = bytePtr;
				}

				return result;
			};

			// byte0 byte1 byte2 byte3
			// 7......0 | 7......0 | 7......0 | 7......0
			// 
			// The bit pointer starts at bit7 of byte0 and moves right until it
			// reaches
			// bit0 of byte0, then goes to bit7 of byte1, etc.
			bitjs.io.BitStream.prototype.peekBits_rtl = function(n,
					movePointers) {
				if (n <= 0 || typeof n != typeof 1) {
					return 0;
				}

				var movePointers = movePointers || false, bytePtr = this.bytePtr, bitPtr = this.bitPtr, result = 0, bytes = this.bytes;

				// keep going until we have no more bits left to peek at
				// TODO: Consider putting all bits from bytes we will need into
				// a variable and then
				// shifting/masking it to just extract the bits we want.
				// This could be considerably faster when reading more than 3 or
				// 4 bits at a time.
				while (n > 0) {

					if (bytePtr >= bytes.length) {
						throw "Error!  Overflowed the bit stream! n=" + n
								+ ", bytePtr=" + bytePtr + ", bytes.length="
								+ bytes.length + ", bitPtr=" + bitPtr;
						return -1;
					}

					var numBitsLeftInThisByte = (8 - bitPtr);
					if (n >= numBitsLeftInThisByte) {
						result <<= numBitsLeftInThisByte;
						result |= (BITMASK[numBitsLeftInThisByte] & bytes[bytePtr]);
						bytePtr++;
						bitPtr = 0;
						n -= numBitsLeftInThisByte;
					} else {
						result <<= n;
						result |= ((bytes[bytePtr] & (BITMASK[n] << (8 - n - bitPtr))) >> (8 - n - bitPtr));

						bitPtr += n;
						n = 0;
					}
				}

				if (movePointers) {
					this.bitPtr = bitPtr;
					this.bytePtr = bytePtr;
				}

				return result;
			};

			// some voodoo magic
			bitjs.io.BitStream.prototype.getBits = function() {
				return (((((this.bytes[this.bytePtr] & 0xff) << 16)
						+ ((this.bytes[this.bytePtr + 1] & 0xff) << 8) + ((this.bytes[this.bytePtr + 2] & 0xff))) >>> (8 - this.bitPtr)) & 0xffff);
			};

			bitjs.io.BitStream.prototype.readBits = function(n) {
				return this.peekBits(n, true);
			};

			// This returns n bytes as a sub-array, advancing the pointer if
			// movePointers
			// is true.
			// Only use this for uncompressed blocks as this throws away
			// remaining bits in
			// the current byte.
			bitjs.io.BitStream.prototype.peekBytes = function(n, movePointers) {
				if (n <= 0 || typeof n != typeof 1) {
					return 0;
				}

				// from http://tools.ietf.org/html/rfc1951#page-11
				// "Any bits of input up to the next byte boundary are ignored."
				while (this.bitPtr != 0) {
					this.readBits(1);
				}

				var movePointers = movePointers || false;
				var bytePtr = this.bytePtr, bitPtr = this.bitPtr;

				var result = this.bytes.subarray(bytePtr, bytePtr + n);

				if (movePointers) {
					this.bytePtr += n;
				}

				return result;
			};

			bitjs.io.BitStream.prototype.readBytes = function(n) {
				return this.peekBytes(n, true);
			};

			/**
			 * This object allows you to peek and consume bytes as numbers and
			 * strings out of an ArrayBuffer.
			 * 
			 * This object is much easier to write than the above BitStream
			 * since everything is byte-aligned.
			 * 
			 * {ArrayBuffer} ab The ArrayBuffer object. {Number} opt_offset The
			 * offset into the ArrayBuffer {Number} opt_length The length of
			 * this BitStream
			 */
			bitjs.io.ByteStream = function(ab, opt_offset, opt_length) {
				var offset = opt_offset || 0;
				var length = opt_length || ab.byteLength;
				this.bytes = new Uint8Array(ab, offset, length);
				this.ptr = 0;
			};

			// peeks at the next n bytes as an unsigned number but does not
			// advance the pointer
			// TODO: This apparently cannot read more than 4 bytes as a number?
			bitjs.io.ByteStream.prototype.peekNumber = function(n) {
				// TODO: return error if n would go past the end of the stream?
				if (n <= 0 || typeof n != typeof 1)
					return -1;

				var result = 0;
				// read from last byte to first byte and roll them in
				var curByte = this.ptr + n - 1;
				while (curByte >= this.ptr) {
					result <<= 8;
					result |= this.bytes[curByte];
					--curByte;
				}
				return result;
			};

			// returns the next n bytes as an unsigned number (or -1 on error)
			// and advances the stream pointer n bytes
			bitjs.io.ByteStream.prototype.readNumber = function(n) {
				var num = this.peekNumber(n);
				this.ptr += n;
				return num;
			};

			// This returns n bytes as a sub-array, advancing the pointer if
			// movePointers
			// is true.
			bitjs.io.ByteStream.prototype.peekBytes = function(n, movePointers) {
				if (n <= 0 || typeof n != typeof 1) {
					return 0;
				}

				var result = this.bytes.subarray(this.ptr, this.ptr + n);

				if (movePointers) {
					this.ptr += n;
				}

				return result;
			};

			bitjs.io.ByteStream.prototype.readBytes = function(n) {
				return this.peekBytes(n, true);
			};

			// peeks at the next n bytes as a string but does not advance the
			// pointer
			bitjs.io.ByteStream.prototype.peekString = function(n) {
				if (n <= 0 || typeof n != typeof 1) {
					return 0;
				}

				var result = "";
				for ( var p = this.ptr, end = this.ptr + n; p < end; ++p) {
					result += String.fromCharCode(this.bytes[p]);
				}
				return result;
			};

			// returns the next n bytes as a string
			// and advances the stream pointer n bytes
			bitjs.io.ByteStream.prototype.readString = function(n) {
				var strToReturn = this.peekString(n);
				this.ptr += n;
				return strToReturn;
			};

			/**
			 * A write-only Byte buffer which uses a Uint8 Typed Array as a
			 * backing store.
			 */
			bitjs.io.ByteBuffer = function(numBytes) {
				if (typeof numBytes != typeof 1 || numBytes <= 0) {
					throw "Error! ByteBuffer initialized with '" + numBytes
							+ "'";
				}
				this.data = new Uint8Array(numBytes);
				this.ptr = 0;
			};

			bitjs.io.ByteBuffer.prototype.insertByte = function(b) {
				// TODO: throw if byte is invalid?
				this.data[this.ptr++] = b;
			};

			bitjs.io.ByteBuffer.prototype.insertBytes = function(bytes) {
				// TODO: throw if bytes is invalid?
				this.data.set(bytes, this.ptr);
				this.ptr += bytes.length;
			};

			// RAR decoding

			// Helper functions.

			var info = function(str) {
				// console.log(str);
			};
			var err = function(bstream, str) {
				bstream.deferred.reject(str);
			};

			// shows a byte value as its hex representation
			var nibble = "0123456789ABCDEF";
			var byteValueToHexString = function(num) {
				return nibble[num >> 4] + nibble[num & 0xF];
			};
			var twoByteValueToHexString = function(num) {
				return nibble[(num >> 12) & 0xF] + nibble[(num >> 8) & 0xF]
						+ nibble[(num >> 4) & 0xF] + nibble[num & 0xF];
			};

			// Volume Types
			var MARK_HEAD = 0x72, MAIN_HEAD = 0x73, FILE_HEAD = 0x74, COMM_HEAD = 0x75, AV_HEAD = 0x76, SUB_HEAD = 0x77, PROTECT_HEAD = 0x78, SIGN_HEAD = 0x79, NEWSUB_HEAD = 0x7a, ENDARC_HEAD = 0x7b;

			// bstream is a bit stream
			var RarVolumeHeader = function(bstream) {

				var headPos = bstream.bytePtr;
				// byte 1,2
				info("Rar Volume Header @" + bstream.bytePtr);

				this.crc = bstream.readBits(16);
				info("  crc=" + this.crc);

				// byte 3
				this.headType = bstream.readBits(8);
				info("  headType=" + this.headType);

				// Get flags
				// bytes 4,5
				this.flags = {};
				this.flags.value = bstream.peekBits(16);

				info("  flags=" + twoByteValueToHexString(this.flags.value));
				switch (this.headType) {
				case MAIN_HEAD:
					this.flags.MHD_VOLUME = !!bstream.readBits(1);
					this.flags.MHD_COMMENT = !!bstream.readBits(1);
					this.flags.MHD_LOCK = !!bstream.readBits(1);
					this.flags.MHD_SOLID = !!bstream.readBits(1);
					this.flags.MHD_PACK_COMMENT = !!bstream.readBits(1);
					this.flags.MHD_NEWNUMBERING = this.flags.MHD_PACK_COMMENT;
					this.flags.MHD_AV = !!bstream.readBits(1);
					this.flags.MHD_PROTECT = !!bstream.readBits(1);
					this.flags.MHD_PASSWORD = !!bstream.readBits(1);
					this.flags.MHD_FIRSTVOLUME = !!bstream.readBits(1);
					this.flags.MHD_ENCRYPTVER = !!bstream.readBits(1);
					bstream.readBits(6); // unused
					break;
				case FILE_HEAD:
				case NEWSUB_HEAD:
					this.flags.LHD_SPLIT_BEFORE = !!bstream.readBits(1); // 0x0001
					this.flags.LHD_SPLIT_AFTER = !!bstream.readBits(1); // 0x0002
					this.flags.LHD_PASSWORD = !!bstream.readBits(1); // 0x0004
					this.flags.LHD_COMMENT = !!bstream.readBits(1); // 0x0008
					this.flags.LHD_SOLID = !!bstream.readBits(1); // 0x0010
					bstream.readBits(3); // unused
					this.flags.LHD_LARGE = !!bstream.readBits(1); // 0x0100
					this.flags.LHD_UNICODE = !!bstream.readBits(1); // 0x0200
					this.flags.LHD_SALT = !!bstream.readBits(1); // 0x0400
					this.flags.LHD_VERSION = !!bstream.readBits(1); // 0x0800
					this.flags.LHD_EXTTIME = !!bstream.readBits(1); // 0x1000
					this.flags.LHD_EXTFLAGS = !!bstream.readBits(1); // 0x2000
					bstream.readBits(2); // unused
					info("  LHD_SPLIT_BEFORE = " + this.flags.LHD_SPLIT_BEFORE);
					break;
				default:
					bstream.readBits(16);
				}

				// byte 6,7
				this.headSize = bstream.readBits(16);
				info("  headSize=" + this.headSize);
				switch (this.headType) {
				case MAIN_HEAD:
					this.highPosAv = bstream.readBits(16);
					this.posAv = bstream.readBits(32);
					if (this.flags.MHD_ENCRYPTVER) {
						this.encryptVer = bstream.readBits(8);
					}
					info("Found MAIN_HEAD with highPosAv=" + this.highPosAv
							+ ", posAv=" + this.posAv);
					break;
				case FILE_HEAD:
				case NEWSUB_HEAD:
					this.packSize = bstream.readBits(32);
					this.unpackedSize = bstream.readBits(32);
					this.hostOS = bstream.readBits(8);
					this.fileCRC = bstream.readBits(32);
					this.fileTime = bstream.readBits(32);
					this.unpVer = bstream.readBits(8);
					this.method = bstream.readBits(8);
					this.nameSize = bstream.readBits(16);
					this.fileAttr = bstream.readBits(32);

					if (this.flags.LHD_LARGE) {
						unrar
								.info("Warning: Reading in LHD_LARGE 64-bit size values");
						this.HighPackSize = bstream.readBits(32);
						this.HighUnpSize = bstream.readBits(32);
					} else {
						this.HighPackSize = 0;
						this.HighUnpSize = 0;
						if (this.unpackedSize == 0xffffffff) {
							this.HighUnpSize = 0x7fffffff;
							this.unpackedSize = 0xffffffff;
						}
					}
					this.fullPackSize = 0;
					this.fullUnpackSize = 0;
					this.fullPackSize |= this.HighPackSize;
					this.fullPackSize <<= 32;
					this.fullPackSize |= this.packSize;

					// read in filename

					this.filename = bstream.readBytes(this.nameSize);
					for ( var _i = 0, _s = ''; _i < this.filename.length; _i++) {
						_s += String.fromCharCode(this.filename[_i]);
					}

					this.filename = _s;

					if (this.flags.LHD_SALT) {
						info("Warning: Reading in 64-bit salt value");
						this.salt = bstream.readBits(64); // 8 bytes
					}

					if (this.flags.LHD_EXTTIME) {
						// 16-bit flags
						var extTimeFlags = bstream.readBits(16);

						// this is adapted straight out of arcread.cpp,
						// Archive::ReadHeader()
						for ( var I = 0; I < 4; ++I) {
							var rmode = extTimeFlags >> ((3 - I) * 4);
							if ((rmode & 8) == 0)
								continue;
							if (I != 0)
								bstream.readBits(16);
							var count = (rmode & 3);
							for ( var J = 0; J < count; ++J)
								bstream.readBits(8);
						}
					}

					if (this.flags.LHD_COMMENT) {
						info("Found a LHD_COMMENT");
					}

					while (headPos + this.headSize > bstream.bytePtr)
						bstream.readBits(1);

					info("Found FILE_HEAD with packSize=" + this.packSize
							+ ", unpackedSize= " + this.unpackedSize
							+ ", hostOS=" + this.hostOS + ", unpVer="
							+ this.unpVer + ", method=" + this.method
							+ ", filename=" + this.filename);

					break;
				default:
					info("Found a header of type 0x"
							+ byteValueToHexString(this.headType));
					// skip the rest of the header bytes (for now)
					bstream.readBytes(this.headSize - 7);
					break;
				}
			};

			var BLOCK_LZ = 0, BLOCK_PPM = 1;

			var rLDecode = [ 0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24,
					28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224 ], rLBits = [
					0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
					4, 4, 4, 4, 5, 5, 5, 5 ], rDBitLengthCounts = [ 4, 2, 2, 2,
					2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 14, 0, 12 ], rSDDecode = [
					0, 4, 8, 16, 32, 64, 128, 192 ], rSDBits = [ 2, 2, 3, 4, 5,
					6, 6, 6 ];

			var rDDecode = [ 0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96,
					128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096,
					6144, 8192, 12288, 16384, 24576, 32768, 49152, 65536,
					98304, 131072, 196608, 262144, 327680, 393216, 458752,
					524288, 589824, 655360, 720896, 786432, 851968, 917504,
					983040 ];

			var rDBits = [ 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7,
					7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15,
					15, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16 ];

			var rLOW_DIST_REP_COUNT = 16;

			var rNC = 299, rDC = 60, rLDC = 17, rRC = 28, rBC = 20, rHUFF_TABLE_SIZE = (rNC
					+ rDC + rRC + rLDC);

			var UnpBlockType = BLOCK_LZ;
			var UnpOldTable = new Array(rHUFF_TABLE_SIZE);

			var BD = { // bitdecode
				DecodeLen : new Array(16),
				DecodePos : new Array(16),
				DecodeNum : new Array(rBC)
			};
			var LD = { // litdecode
				DecodeLen : new Array(16),
				DecodePos : new Array(16),
				DecodeNum : new Array(rNC)
			};
			var DD = { // distdecode
				DecodeLen : new Array(16),
				DecodePos : new Array(16),
				DecodeNum : new Array(rDC)
			};
			var LDD = { // low dist decode
				DecodeLen : new Array(16),
				DecodePos : new Array(16),
				DecodeNum : new Array(rLDC)
			};
			var RD = { // rep decode
				DecodeLen : new Array(16),
				DecodePos : new Array(16),
				DecodeNum : new Array(rRC)
			};

			var rBuffer;

			// read in Huffman tables for RAR
			function RarReadTables(bstream) {
				var BitLength = new Array(rBC), Table = new Array(
						rHUFF_TABLE_SIZE);

				// before we start anything we need to get byte-aligned
				bstream.readBits((8 - bstream.bitPtr) & 0x7);

				if (bstream.readBits(1)) {
					err(bstream.deferred, "Error!  PPM not implemented yet");
					return;
				}

				if (!bstream.readBits(1)) { // discard old table
					for ( var i = UnpOldTable.length; i--;)
						UnpOldTable[i] = 0;
				}

				// read in bit lengths
				for ( var I = 0; I < rBC; ++I) {

					var Length = bstream.readBits(4);
					if (Length == 15) {
						var ZeroCount = bstream.readBits(4);
						if (ZeroCount == 0) {
							BitLength[I] = 15;
						} else {
							ZeroCount += 2;
							while (ZeroCount-- > 0 && I < rBC)
								BitLength[I++] = 0;
							--I;
						}
					} else {
						BitLength[I] = Length;
					}
				}

				// now all 20 bit lengths are obtained, we construct the Huffman
				// Table:

				RarMakeDecodeTables(BitLength, 0, BD, rBC);

				var TableSize = rHUFF_TABLE_SIZE;
				// console.log(DecodeLen, DecodePos, DecodeNum);
				for ( var i = 0; i < TableSize;) {
					var num = RarDecodeNumber(bstream, BD);
					if (num < 16) {
						Table[i] = (num + UnpOldTable[i]) & 0xf;
						i++;
					} else if (num < 18) {
						var N = (num == 16) ? (bstream.readBits(3) + 3)
								: (bstream.readBits(7) + 11);

						while (N-- > 0 && i < TableSize) {
							Table[i] = Table[i - 1];
							i++;
						}
					} else {
						var N = (num == 18) ? (bstream.readBits(3) + 3)
								: (bstream.readBits(7) + 11);

						while (N-- > 0 && i < TableSize) {
							Table[i++] = 0;
						}
					}
				}

				RarMakeDecodeTables(Table, 0, LD, rNC);
				RarMakeDecodeTables(Table, rNC, DD, rDC);
				RarMakeDecodeTables(Table, rNC + rDC, LDD, rLDC);
				RarMakeDecodeTables(Table, rNC + rDC + rLDC, RD, rRC);

				for ( var i = UnpOldTable.length; i--;) {
					UnpOldTable[i] = Table[i];
				}
				return true;
			}

			function RarDecodeNumber(bstream, dec) {
				var DecodeLen = dec.DecodeLen, DecodePos = dec.DecodePos, DecodeNum = dec.DecodeNum;
				var bitField = bstream.getBits() & 0xfffe;
				// some sort of rolled out binary search
				var bits = ((bitField < DecodeLen[8]) ? ((bitField < DecodeLen[4]) ? ((bitField < DecodeLen[2]) ? ((bitField < DecodeLen[1]) ? 1
						: 2)
						: ((bitField < DecodeLen[3]) ? 3 : 4))
						: (bitField < DecodeLen[6]) ? ((bitField < DecodeLen[5]) ? 5
								: 6)
								: ((bitField < DecodeLen[7]) ? 7 : 8))
						: ((bitField < DecodeLen[12]) ? ((bitField < DecodeLen[10]) ? ((bitField < DecodeLen[9]) ? 9
								: 10)
								: ((bitField < DecodeLen[11]) ? 11 : 12))
								: (bitField < DecodeLen[14]) ? ((bitField < DecodeLen[13]) ? 13
										: 14)
										: 15));
				bstream.readBits(bits);
				var N = DecodePos[bits]
						+ ((bitField - DecodeLen[bits - 1]) >>> (16 - bits));

				return DecodeNum[N];
			}

			function RarMakeDecodeTables(BitLength, offset, dec, size) {
				var DecodeLen = dec.DecodeLen, DecodePos = dec.DecodePos, DecodeNum = dec.DecodeNum;
				var LenCount = [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ], TmpPos = [
						0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ], N = 0, M = 0;
				for ( var i = DecodeNum.length; i--;)
					DecodeNum[i] = 0;
				for ( var i = 0; i < size; i++) {
					LenCount[BitLength[i + offset] & 0xF]++;
				}
				LenCount[0] = 0;
				TmpPos[0] = 0;
				DecodePos[0] = 0;
				DecodeLen[0] = 0;

				for ( var I = 1; I < 16; ++I) {
					N = 2 * (N + LenCount[I]);
					M = (N << (15 - I));
					if (M > 0xFFFF)
						M = 0xFFFF;
					DecodeLen[I] = M;
					DecodePos[I] = DecodePos[I - 1] + LenCount[I - 1];
					TmpPos[I] = DecodePos[I];
				}
				for (I = 0; I < size; ++I)
					if (BitLength[I + offset] != 0)
						DecodeNum[TmpPos[BitLength[offset + I] & 0xF]++] = I;

			}

			// TODO: implement
			function Unpack15(bstream, Solid) {
				err(bstream.deferred,
						"Error!  RAR 1.5 compression not supported");
			}

			function Unpack20(bstream, Solid) {
				var destUnpSize = rBuffer.data.length;
				var oldDistPtr = 0;

				RarReadTables20(bstream);
				while (destUnpSize > rBuffer.ptr) {
					var num = RarDecodeNumber(bstream, LD);
					if (num < 256) {
						rBuffer.insertByte(num);
						continue;
					}
					if (num > 269) {
						var Length = rLDecode[num -= 270] + 3;
						if ((Bits = rLBits[num]) > 0) {
							Length += bstream.readBits(Bits);
						}
						var DistNumber = RarDecodeNumber(bstream, DD);
						var Distance = rDDecode[DistNumber] + 1;
						if ((Bits = rDBits[DistNumber]) > 0) {
							Distance += bstream.readBits(Bits);
						}
						if (Distance >= 0x2000) {
							Length++;
							if (Distance >= 0x40000)
								Length++;
						}
						lastLength = Length;
						lastDist = rOldDist[oldDistPtr++ & 3] = Distance;
						RarCopyString(Length, Distance);
						continue;
					}
					if (num == 269) {
						RarReadTables20(bstream);
						continue;
					}
					if (num == 256) {
						lastDist = rOldDist[oldDistPtr++ & 3] = lastDist;
						RarCopyString(lastLength, lastDist);
						continue;
					}
					if (num < 261) {
						var Distance = rOldDist[(oldDistPtr - (num - 256)) & 3];
						var LengthNumber = RarDecodeNumber(bstream, RD);
						var Length = rLDecode[LengthNumber] + 2;
						if ((Bits = rLBits[LengthNumber]) > 0) {
							Length += bstream.readBits(Bits);
						}
						if (Distance >= 0x101) {
							Length++;
							if (Distance >= 0x2000) {
								Length++
								if (Distance >= 0x40000)
									Length++;
							}
						}
						lastLength = Length;
						lastDist = rOldDist[oldDistPtr++ & 3] = Distance;
						RarCopyString(Length, Distance);
						continue;
					}
					if (num < 270) {
						var Distance = rSDDecode[num -= 261] + 1;
						if ((Bits = rSDBits[num]) > 0) {
							Distance += bstream.readBits(Bits);
						}
						lastLength = 2;
						lastDist = rOldDist[oldDistPtr++ & 3] = Distance;
						RarCopyString(2, Distance);
						continue;
					}

				}
			}

			var rNC20 = 298, rDC20 = 48, rRC20 = 28, rBC20 = 19, rMC20 = 257;

			var UnpOldTable20 = new Array(rMC20 * 4);

			function RarReadTables20(bstream) {
				var BitLength = new Array(rBC20);
				var Table = new Array(rMC20 * 4);
				var TableSize, N, I;
				var AudioBlock = bstream.readBits(1);
				if (!bstream.readBits(1))
					for ( var i = UnpOldTable20.length; i--;)
						UnpOldTable20[i] = 0;
				TableSize = rNC20 + rDC20 + rRC20;
				for ( var I = 0; I < rBC20; I++)
					BitLength[I] = bstream.readBits(4);
				RarMakeDecodeTables(BitLength, 0, BD, rBC20);
				I = 0;
				while (I < TableSize) {
					var num = RarDecodeNumber(bstream, BD);
					if (num < 16) {
						Table[I] = num + UnpOldTable20[I] & 0xf;
						I++;
					} else if (num == 16) {
						N = bstream.readBits(2) + 3;
						while (N-- > 0 && I < TableSize) {
							Table[I] = Table[I - 1];
							I++;
						}
					} else {
						if (num == 17) {
							N = bstream.readBits(3) + 3;
						} else {
							N = bstream.readBits(7) + 11;
						}
						while (N-- > 0 && I < TableSize) {
							Table[I++] = 0;
						}
					}
				}
				RarMakeDecodeTables(Table, 0, LD, rNC20);
				RarMakeDecodeTables(Table, rNC20, DD, rDC20);
				RarMakeDecodeTables(Table, rNC20 + rDC20, RD, rRC20);
				for ( var i = UnpOldTable20.length; i--;)
					UnpOldTable20[i] = Table[i];
			}

			var lowDistRepCount = 0, prevLowDist = 0;

			var rOldDist = [ 0, 0, 0, 0 ];
			var lastDist;
			var lastLength;

			function Unpack29(bstream, Solid) {
				// lazy initialize rDDecode and rDBits

				var DDecode = new Array(rDC);
				var DBits = new Array(rDC);

				var Dist = 0, BitLength = 0, Slot = 0;

				for ( var I = 0; I < rDBitLengthCounts.length; I++, BitLength++) {
					for ( var J = 0; J < rDBitLengthCounts[I]; J++, Slot++, Dist += (1 << BitLength)) {
						DDecode[Slot] = Dist;
						DBits[Slot] = BitLength;
					}
				}

				var Bits;
				// tablesRead = false;

				rOldDist = [ 0, 0, 0, 0 ]

				lastDist = 0;
				lastLength = 0;

				for ( var i = UnpOldTable.length; i--;)
					UnpOldTable[i] = 0;

				// read in Huffman tables
				RarReadTables(bstream);

				while (true) {
					var num = RarDecodeNumber(bstream, LD);

					if (num < 256) {
						rBuffer.insertByte(num);
						continue;
					}
					if (num >= 271) {
						var Length = rLDecode[num -= 271] + 3;
						if ((Bits = rLBits[num]) > 0) {
							Length += bstream.readBits(Bits);
						}
						var DistNumber = RarDecodeNumber(bstream, DD);
						var Distance = DDecode[DistNumber] + 1;
						if ((Bits = DBits[DistNumber]) > 0) {
							if (DistNumber > 9) {
								if (Bits > 4) {
									Distance += ((bstream.getBits() >>> (20 - Bits)) << 4);
									bstream.readBits(Bits - 4);
									// todo: check this
								}
								if (lowDistRepCount > 0) {
									lowDistRepCount--;
									Distance += prevLowDist;
								} else {
									var LowDist = RarDecodeNumber(bstream, LDD);
									if (LowDist == 16) {
										lowDistRepCount = rLOW_DIST_REP_COUNT - 1;
										Distance += prevLowDist;
									} else {
										Distance += LowDist;
										prevLowDist = LowDist;
									}
								}
							} else {
								Distance += bstream.readBits(Bits);
							}
						}
						if (Distance >= 0x2000) {
							Length++;
							if (Distance >= 0x40000) {
								Length++;
							}
						}
						RarInsertOldDist(Distance);
						RarInsertLastMatch(Length, Distance);
						RarCopyString(Length, Distance);
						continue;
					}
					if (num == 256) {
						if (!RarReadEndOfBlock(bstream))
							break;

						continue;
					}
					if (num == 257) {
						// console.log("READVMCODE");
						if (!RarReadVMCode(bstream))
							break;
						continue;
					}
					if (num == 258) {
						if (lastLength != 0) {
							RarCopyString(lastLength, lastDist);
						}
						continue;
					}
					if (num < 263) {
						var DistNum = num - 259;
						var Distance = rOldDist[DistNum];

						for ( var I = DistNum; I > 0; I--) {
							rOldDist[I] = rOldDist[I - 1];
						}
						rOldDist[0] = Distance;

						var LengthNumber = RarDecodeNumber(bstream, RD);
						var Length = rLDecode[LengthNumber] + 2;
						if ((Bits = rLBits[LengthNumber]) > 0) {
							Length += bstream.readBits(Bits);
						}
						RarInsertLastMatch(Length, Distance);
						RarCopyString(Length, Distance);
						continue;
					}
					if (num < 272) {
						var Distance = rSDDecode[num -= 263] + 1;
						if ((Bits = rSDBits[num]) > 0) {
							Distance += bstream.readBits(Bits);
						}
						RarInsertOldDist(Distance);
						RarInsertLastMatch(2, Distance);
						RarCopyString(2, Distance);
						continue;
					}

				}
			}

			function RarReadEndOfBlock(bstream) {

				var NewTable = false, NewFile = false;
				if (bstream.readBits(1)) {
					NewTable = true;
				} else {
					NewFile = true;
					NewTable = !!bstream.readBits(1);
				}
				// tablesRead = !NewTable;
				return !(NewFile || NewTable && !RarReadTables(bstream));
			}

			function RarReadVMCode(bstream) {
				var FirstByte = bstream.readBits(8);
				var Length = (FirstByte & 7) + 1;
				if (Length == 7) {
					Length = bstream.readBits(8) + 7;
				} else if (Length == 8) {
					Length = bstream.readBits(16);
				}
				var vmCode = [];
				for ( var I = 0; I < Length; I++) {
					// do something here with cheking readbuf
					vmCode.push(bstream.readBits(8));
				}
				return RarAddVMCode(FirstByte, vmCode, Length, bstream.deferred);
			}

			function RarAddVMCode(firstByte, vmCode, length, deferred) {
				// console.log(vmCode);
				if (vmCode.length > 0) {
					err(deferred, "Error! RarVM not supported yet!");
				}
				return true;
			}

			function RarInsertLastMatch(length, distance) {
				lastDist = distance;
				lastLength = length;
			}

			function RarInsertOldDist(distance) {
				rOldDist.splice(3, 1);
				rOldDist.splice(0, 0, distance);
			}

			// this is the real function, the other one is for debugging
			function RarCopyString(length, distance) {
				var destPtr = rBuffer.ptr - distance;
				if (destPtr < 0) {
					var l = rOldBuffers.length;
					while (destPtr < 0) {
						destPtr = rOldBuffers[--l].data.length + destPtr
					}
					// TODO: lets hope that it never needs to read beyond file
					// boundaries
					while (length--)
						rBuffer.insertByte(rOldBuffers[l].data[destPtr++]);

				}
				if (length > distance) {
					while (length--)
						rBuffer.insertByte(rBuffer.data[destPtr++]);
				} else {
					rBuffer.insertBytes(rBuffer.data.subarray(destPtr, destPtr
							+ length));
				}

			}

			var rOldBuffers = []
			// v must be a valid RarVolume
			function unpack(v) {

				// TODO: implement what happens when unpVer is < 15
				var Ver = v.header.unpVer <= 15 ? 15 : v.header.unpVer, Solid = v.header.LHD_SOLID, bstream = new bitjs.io.BitStream(
						v.fileData.buffer, true /* rtl */,
						v.fileData.byteOffset, v.fileData.byteLength);

				rBuffer = new bitjs.io.ByteBuffer(v.header.unpackedSize);

				info("Unpacking " + v.filename + " RAR v" + Ver);

				switch (Ver) {
				case 15: // rar 1.5 compression
					Unpack15(bstream, Solid);
					break;
				case 20: // rar 2.x compression
				case 26: // files larger than 2GB
					Unpack20(bstream, Solid);
					break;
				case 29: // rar 3.x compression
				case 36: // alternative hash
					Unpack29(bstream, Solid);
					break;
				} // switch(method)

				rOldBuffers.push(rBuffer);
				// TODO: clear these old buffers when there's over 4MB of
				// history
				return rBuffer.data;
			}

			// bstream is a bit stream
			var RarLocalFile = function(bstream) {

				this.header = new RarVolumeHeader(bstream);
				this.filename = this.header.filename;

				if (this.header.headType != FILE_HEAD
						&& this.header.headType != ENDARC_HEAD
						&& this.header.headType != NEWSUB_HEAD) {
					this.isValid = false;
					err(bstream.deferred,
							"Error! RAR Volume did not include a FILE_HEAD header ");
				} else {
					// read in the compressed data
					this.fileData = null;
					if (this.header.packSize > 0) {
						this.fileData = bstream.readBytes(this.header.packSize);
						this.isValid = true;
					}
				}
			};

			RarLocalFile.prototype.unrar = function() {

				if (!this.header.flags.LHD_SPLIT_BEFORE) {
					// unstore file
					if (this.header.method == 0x30) {
						info("Unstore " + this.filename);
						this.isValid = true;
					} else {
						this.isValid = true;
						this.fileData = unpack(this);
					}
				}
			}

			var unrar = function(arrayBuffer) {

				arrayBuffer = base64ToArrayBuffer(arrayBuffer);

				var deferred = new Deferred();

				var bstream = new bitjs.io.BitStream(arrayBuffer, false /* rtl */);

				bstream.deferred = deferred;

				setTimeout(function() {
					var header = new RarVolumeHeader(bstream);
					if (header.crc == 0x6152 && header.headType == 0x72
							&& header.flags.value == 0x1A21
							&& header.headSize == 7) {
						info("Found RAR signature");

						var mhead = new RarVolumeHeader(bstream);
						if (mhead.headType != MAIN_HEAD) {
							err(deferred,
									"Error! RAR did not include a MAIN_HEAD header");
						} else {
							var localFiles = [], localFile = null;
							do {
								try {
									localFile = new RarLocalFile(bstream);
									info("RAR localFile isValid="
											+ localFile.isValid
											+ ", volume packSize="
											+ localFile.header.packSize);
									if (localFile && localFile.isValid
											&& localFile.header.packSize > 0) {
										if (localFile.header.headType != NEWSUB_HEAD)
											localFiles.push(localFile);
									} else if (localFile.header.packSize == 0
											&& localFile.header.unpackedSize == 0) {
										localFile.isValid = true;
									}
								} catch (err) {
									break;
								}
								// info("bstream" +
								// bstream.bytePtr+"/"+bstream.bytes.length);
							} while (localFile.isValid);

							// now we have all information but things are
							// unpacked
							// TODO: unpack
							localFiles = localFiles.sort(function(a, b) {
								// extract the number at the end of both
								// filenames
								var aname = a.filename;
								var bname = b.filename;
								return aname > bname ? 1 : -1;
								/*
								 * var aindex = aname.length, bindex =
								 * bname.length; // Find the last number
								 * character from the back of the filename.
								 * while (aname[aindex-1] < '0' ||
								 * aname[aindex-1] > '9') --aindex; while
								 * (bname[bindex-1] < '0' || bname[bindex-1] >
								 * '9') --bindex; // Find the first number
								 * character from the back of the filename while
								 * (aname[aindex-1] >= '0' && aname[aindex-1] <=
								 * '9') --aindex; while (bname[bindex-1] >= '0' &&
								 * bname[bindex-1] <= '9') --bindex; // parse
								 * them into numbers and return comparison var
								 * anum = parseInt(aname.substr(aindex), 10),
								 * bnum = parseInt(bname.substr(bindex), 10);
								 * return bnum - anum;
								 */
							});

							info(localFiles.map(function(a) {
								return a.filename
							}).join(', '));

							var attachments = [];

							var f = function(i) {

								if (deferred.isCanceled()) {
									return;
								}

								if (i >= localFiles.length) {
									deferred.resolve(attachments);
									return;
								}

								var localfile = localFiles[i];

								// update progress

								deferred.progress({
									file : localfile.header.filename,
									index : i,
									total : localFiles.length
								});

								// actually do the unzipping
								localfile.unrar();

								if (localfile.isValid) {
									attachments
											.push({
												filename : localfile.filename,
												data : Uint8ArrayToBase64(localfile.fileData)
											});
								}
								setTimeout(function() {
									f(i + 1);
								});
							};
							setTimeout(function() {
								f(0);
							});
						}
					} else {
						err(deferred, "Invalid RAR file");
					}
				});
				return deferred;
			};

			function base64ToArrayBuffer(base64) {
				var binary_string = atob(base64);
				var len = binary_string.length;
				var bytes = new Uint8Array(len);
				for ( var i = 0; i < len; i++) {
					var ascii = binary_string.charCodeAt(i);
					bytes[i] = ascii;
				}
				return bytes.buffer;
			}

			var blockSize = 100000;

			function Uint8ArrayToBase64(bytes) {
				var binary = '';
				var len = bytes.byteLength;
				for ( var start = 0; start < len; start += blockSize) {
					binary += String.fromCharCode.apply(null, bytes.subarray(
							start, start + blockSize));
				}
				return btoa(binary);
			}

			return unrar;

		});
