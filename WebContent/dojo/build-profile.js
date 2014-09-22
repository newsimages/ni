var profile = (function(){
	return {
		"action"				  : "release",

		// point basePath to build/
		"basePath"				  : "../../",
		"releaseDir"			  : "./tmp",
		"selectorEngine"		  : "acme",
		"optimize"				  : "closure",
		"layerOptimize"			  : "closure",
		"stripConsole"			  : "normal",
		"copyTests"				  : false,
		
		"cssImportIgnore"		  : "../dijit.css",
		"cssOptimize"			  : "comments.keepLines",
		"mini"					  : true,
		"webkitMobile"			  : true,
		// specificies a list of locale to generate flattened nls bundle, or set
		// it to null to rely on new 1.7 behavior
		"localeList"			  : "en-us",
		// specifies the flattened bundles to copy in the layers (it avoids a separate file and separate request)
		//"includeLocales"		  : ["en-us"],
		
		// comment out these options if you load the layers with <script> tag (and you should not!)
		// instead of require(..) them.
		//"insertAbsMids"			  : true,
		//"compat" : 1.6,
		//"noref" : true,
		
		packages:[
			{ name:"dojo", location:"../dojo"},
			{ name:"dojox", location:"../dojox"},
			{ name:"dijit", location:"../dijit"}
		],
		
		staticHasFeatures: {

			// The trace & log APIs are used for debugging the loader, so we don�t need them in the build
			'dojo-trace-api':0,
			// Disables the logging code of the loader
			'dojo-log-api':0,
			// This causes normally private loader data to be exposed for debugging, so we don�t need that either
			'dojo-publish-privates':0,
			// no sync loader. Enable if legacy api support like dojo.require() is needed. 
			'dojo-sync-loader':0,
			// needed to support legacy i18n api
			'dojo-v1x-i18n-Api':1,
			// Disables some of the error handling when loading modules.
			'config-dojo-loader-catches': 0,
			// Disables code dealing with modules that don't load
			'dojo-timeout-api': 0,
			// Disable support for legacy IE event behaviour API (attachEvent versus attachEventListener).
			'ie-event-behavior': 0,
			// We aren�t loading tests in production
			'dojo-test-sniff':0,
			// Don't add replacement console
			'dojo-guarantee-console': 0

			// Disables some diagnostic information ?
			// 'dojo-debug-messages': 0,
			// Assumes that all modules are AMD ?
			// 'dojo-amd-factory-scan': 0,

		},

		layers: {
			"dojo/dojo" : {
				customBase: true,
				boot: true,
				include: [
					"dojo/dojo",
					"dojo/i18n",
					"dojo/parser",
					"dojo/request",
					"dojox/mobile",
					"dojox/mobile/_base",
					"dojox/mobile/_ComboBoxMenu",
					"dojox/mobile/_ContentPaneMixin",
					"dojox/mobile/_css3",
					"dojox/mobile/_DataListMixin",
					"dojox/mobile/_DataMixin",
					"dojox/mobile/_DatePickerMixin",
					"dojox/mobile/_EditableIconMixin",
					"dojox/mobile/_EditableListMixin",
					"dojox/mobile/_ExecScriptMixin",
					"dojox/mobile/_IconItemPane",
					"dojox/mobile/_ItemBase",
					"dojox/mobile/_ListTouchMixin",
					"dojox/mobile/_maskUtils",
					"dojox/mobile/_PickerBase",
					"dojox/mobile/_PickerChooser",
					"dojox/mobile/_ScrollableMixin",
					"dojox/mobile/_StoreListMixin",
					"dojox/mobile/_StoreMixin",
					"dojox/mobile/_TimePickerMixin",
					"dojox/mobile/Accordion",
					"dojox/mobile/Audio",
					"dojox/mobile/Badge",
					"dojox/mobile/bookmarkable",
					"dojox/mobile/Button",
					"dojox/mobile/Carousel",
					"dojox/mobile/CarouselItem",
					"dojox/mobile/CheckBox",
					"dojox/mobile/ComboBox", // experimental
					"dojox/mobile/common",
					"dojox/mobile/compat",
					"dojox/mobile/Container",
					"dojox/mobile/ContentPane",
					"dojox/mobile/DataCarousel",
					"dojox/mobile/DatePicker",
					"dojox/mobile/EdgeToEdgeCategory",
					"dojox/mobile/EdgeToEdgeDataList",
					"dojox/mobile/EdgeToEdgeList",
					"dojox/mobile/EdgeToEdgeStoreList",
					"dojox/mobile/ExpandingTextArea",
					"dojox/mobile/FilteredListMixin",
					"dojox/mobile/FixedSplitter",
					"dojox/mobile/FixedSplitterPane",
					"dojox/mobile/FormLayout",
					"dojox/mobile/GridLayout",
					"dojox/mobile/Heading",
					"dojox/mobile/i18n",
					"dojox/mobile/Icon",
					"dojox/mobile/IconContainer",
					"dojox/mobile/IconItem",
					"dojox/mobile/IconMenu",
					"dojox/mobile/IconMenuItem",
					"dojox/mobile/iconUtils",
					"dojox/mobile/lazyLoadUtils",
					"dojox/mobile/ListItem",
					"dojox/mobile/LongListMixin",
					"dojox/mobile/Opener",
					"dojox/mobile/Overlay",
					"dojox/mobile/PageIndicator",
					"dojox/mobile/pageTurningUtils",
					"dojox/mobile/Pane",
					//"dojox/mobile/parser",
					"dojox/mobile/ProgressBar",
					"dojox/mobile/ProgressIndicator",
					"dojox/mobile/RadioButton",
					"dojox/mobile/Rating",
					"dojox/mobile/RoundRect",
					"dojox/mobile/RoundRectCategory",
					"dojox/mobile/RoundRectDataList",
					"dojox/mobile/RoundRectList",
					"dojox/mobile/RoundRectStoreList",
					"dojox/mobile/ScreenSizeAware", // experimental
					"dojox/mobile/scrollable",
					"dojox/mobile/ScrollablePane",
					"dojox/mobile/ScrollableView",
					"dojox/mobile/SearchBox",
					"dojox/mobile/SimpleDialog",
					"dojox/mobile/Slider",
					"dojox/mobile/sniff",
					"dojox/mobile/SpinWheel",
					"dojox/mobile/SpinWheelDatePicker",
					"dojox/mobile/SpinWheelSlot",
					"dojox/mobile/SpinWheelTimePicker",
					"dojox/mobile/StoreCarousel",
					"dojox/mobile/SwapView",
					"dojox/mobile/Switch",
					"dojox/mobile/TabBar",
					"dojox/mobile/TabBarButton",
					"dojox/mobile/TextArea",
					"dojox/mobile/TextBox",
					"dojox/mobile/TimePicker",
					"dojox/mobile/ToggleButton",
					"dojox/mobile/ToolBarButton",
					"dojox/mobile/Tooltip",
					"dojox/mobile/transition",
					"dojox/mobile/TransitionEvent",
					"dojox/mobile/TreeView", // experimental
					"dojox/mobile/uacss",
					"dojox/mobile/ValuePicker",
					"dojox/mobile/ValuePickerDatePicker",
					"dojox/mobile/ValuePickerSlot",
					"dojox/mobile/ValuePickerTimePicker",
					"dojox/mobile/Video",
					"dojox/mobile/View",
					"dojox/mobile/ViewController",
					"dojox/mobile/viewRegistry",
					"dojox/mobile/dh/ContentTypeMap",
					"dojox/mobile/dh/DataHandler",
					"dojox/mobile/dh/HtmlContentHandler",
					"dojox/mobile/dh/HtmlScriptContentHandler",
					"dojox/mobile/dh/JsonContentHandler",
					"dojox/mobile/dh/PatternFileTypeMap",
					"dojox/mobile/dh/StringDataSource",
					"dojox/mobile/dh/SuffixFileTypeMap",
					"dojox/mobile/dh/UrlDataSource"
				]
			}
		}
	};
})();
