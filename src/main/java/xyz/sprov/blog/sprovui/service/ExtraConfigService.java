package xyz.sprov.blog.sprovui.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import xyz.sprov.blog.sprovui.bean.InboundTraffic;
import xyz.sprov.blog.sprovui.util.Context;
import xyz.sprov.blog.sprovui.util.V2ctlUtil;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class ExtraConfigService {

    private ThreadService threadService = Context.threadService;

    private String configPath = "/etc/sprov-ui/v2ray-extra-config.json";

    private JSONObject config;

    private Lock writeLock = new ReentrantLock();

    public ExtraConfigService() {
        File file = new File(configPath);
        if (!file.exists() || !file.isFile()) {
            try {
                FileUtils.deleteQuietly(file);
                config = JSONObject.parseObject("{'inbounds': []}");
                writeConfig(config);
            } catch (Exception e) {
                System.err.println("创建配置文件 /etc/sprov-ui/v2ray-extra-config.json 失败：" + e.getMessage());
                System.exit(-1);
            }
        } else {
            try {
                config = readConfig();
            } catch (Exception e) {
                System.err.println("读取配置文件 /etc/sprov-ui/v2ray-extra-config.json 失败：" + e.getMessage());
                System.exit(-1);
            }
        }
        threadService.scheduleAtFixedRate(new UpdateConfigThread(), 1, 1, TimeUnit.MINUTES);
    }

    public Map<String, JSONObject> getTagInboundMap() {
        JSONArray inbounds = getInbounds();
        Map<String, JSONObject> map = new HashMap<>();
        inbounds.forEach(obj -> {
            JSONObject inbound = (JSONObject) obj;
            map.put(inbound.getString("tag"), inbound);
        });
        return map;
    }

    public JSONObject getExtraConfig() throws IOException {
        return JSONObject.parseObject(config());
    }

    public String config() throws IOException {
        return FileUtils.readFileToString(new File(configPath), "UTF-8");
    }

    private JSONObject readConfig() throws IOException {
        return JSONObject.parseObject(FileUtils.readFileToString(new File(configPath), "UTF-8"));
    }

    private void writeConfig(JSONObject config) throws IOException {
        try {
            writeLock.lock();
            String str = JSON.toJSONString(config, true);
            FileUtils.write(new File(configPath), str, "UTF-8");
        } finally {
            writeLock.unlock();
        }
    }

    public JSONArray getInbounds() {
        return config.getJSONArray("inbounds");
    }

    public JSONObject getInboundByTag(String tag) {
        JSONArray inbounds = getInbounds();
        for (Object obj : inbounds) {
            JSONObject inbound = (JSONObject) obj;
            if (tag.equals(inbound.getString("tag"))) {
                return inbound;
            }
        }
        return null;
    }

    public void resetTraffic(String tag) throws IOException {
        JSONObject inbound = getInboundByTag(tag);
        if (inbound == null) {
            return;
        }
        inbound.put("downlink", 0);
        inbound.put("uplink", 0);
        writeConfig(config);
    }

    public void resetAllTraffic() throws IOException {
        foreachInbound(inbound -> {
            inbound.put("downlink", 0);
            inbound.put("uplink", 0);
        });
        writeConfig(config);
    }

    private void foreachInbound(Consumer<JSONObject> consumer) {
        JSONArray inbounds = getInbounds();
        for (Object obj : inbounds) {
            consumer.accept((JSONObject) obj);
        }
    }

    public void addInboundTraffic(String tag, long down, long up) {
        JSONObject inbound = getInboundByTag(tag);
        if (inbound == null) {
            throw new RuntimeException("tag 标识为 <" + tag + "> 的 inbound 不存在");
        }
        addInboundTraffic(inbound, down, up);
    }

    private boolean addInboundTraffic(JSONObject inbound, long down, long up) {
        if (down == 0 && up == 0) {
            return false;
        }
        Long downlink = inbound.getLong("downlink");
        if (downlink == null) {
            downlink = 0L;
        }
        inbound.put("downlink", downlink + down);
        Long uplink = inbound.getLong("uplink");
        if (uplink == null) {
            uplink = 0L;
        }
        inbound.put("uplink", uplink + up);
        return true;
    }

    private class UpdateConfigThread implements Runnable {

        @Override
        public void run() {
            try {
                Map<String, InboundTraffic> map = V2ctlUtil.getInboundTraffics(true);
                boolean updated = false;
                JSONArray inbounds = getInbounds();
                Iterator<Object> iterator = inbounds.iterator();
                while (iterator.hasNext()) {
                    JSONObject inbound = (JSONObject) iterator.next();
                    String tag = inbound.getString("tag");
                    InboundTraffic traffic = map.get(tag);
                    if (traffic == null) {
                        iterator.remove();
                        updated = true;
                    } else if (updateInboundTraffic(inbound, traffic)) {
                        updated = true;
                    }
                    map.remove(tag);
                }
                if (!map.isEmpty()) {
                    updated = true;
                    map.forEach((tag, inboundTraffic) -> addInbound(inbounds, inboundTraffic));
                }
                if (updated) {
                    writeConfig(config);
                }
            } catch (Exception e) {
                System.err.println("更新流量数据失败，请检查 v2ray 是否启动（若此消息未经常出现，请忽略）：" + e);
            }
        }

        private boolean updateInboundTraffic(JSONObject inbound, InboundTraffic inboundTraffic) {
            if (inboundTraffic == null) {
                return false;
            }
            return addInboundTraffic(inbound, inboundTraffic.getDownlink(), inboundTraffic.getUplink());
        }

        private void addInbound(JSONArray inbounds, InboundTraffic inboundTraffic) {
            JSONObject inbound = JSONObject.parseObject(JSONObject.toJSONString(inboundTraffic));
            inbounds.add(inbound);
        }
    }

}
