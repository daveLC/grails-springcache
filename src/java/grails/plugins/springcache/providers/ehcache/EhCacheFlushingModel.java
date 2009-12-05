package grails.plugins.springcache.providers.ehcache;

import java.util.Collection;
import java.util.Collections;
import grails.plugins.springcache.cache.FlushingModel;

class EhCacheFlushingModel extends FlushingModel {

	private final Collection<String> cacheNames;

	public EhCacheFlushingModel(String id, Collection<String> cacheNames) {
		super(id);
		this.cacheNames = Collections.unmodifiableCollection(cacheNames);
	}

	public Collection<String> getCacheNames() {
		return cacheNames;
	}

}