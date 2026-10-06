package company.vk.edu.distrib.compute.katyadoinikova.kv;

import company.vk.edu.distrib.compute.AbstractHttpServiceFactory;
import company.vk.edu.distrib.compute.kv.KVService;
import company.vk.edu.distrib.compute.kv.KVServiceTest;

@KVServiceTest
public final class KVServiceFactory extends AbstractHttpServiceFactory<KVService> {
    @Override
    protected KVService doCreate(int port) {
        return new PersistentKVService(port);
    }
}
