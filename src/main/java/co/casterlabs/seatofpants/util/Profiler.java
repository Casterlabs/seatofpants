package co.casterlabs.seatofpants.util;

import java.util.LinkedList;
import java.util.List;

import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import lombok.NonNull;
import lombok.SneakyThrows;

public class Profiler {
    public final List<Profile> profiles = new LinkedList<>();
    public double timeSpent_ms = 0;

    @SuppressWarnings("unchecked")
    @SneakyThrows
    public <T> T start(@NonNull String name, @NonNull ProfileTaskReturning task) {
        long start_ns = System.nanoTime();
        Object result = task.run();
        long end_ns = System.nanoTime();

        double took_ms = (end_ns - start_ns) / 1000000d;
        this.log(name, took_ms);

        return (T) result;
    }

    public void start(@NonNull String name, @NonNull ProfileTask task) {
        this.start(name, () -> {
            task.run();
            return null;
        });
    }

    public void log(@NonNull String name, double took_ms) {
        this.profiles.add(new Profile(name, took_ms));
        this.timeSpent_ms += took_ms;
    }

    public JsonObject toJson() {
        JsonObject profile = new JsonObject();
        for (Profile p : this.profiles) {
            profile.put(p.name(), p.took_ms());
        }
        return profile;
    }

    public static interface ProfileTask {

        public void run() throws Throwable;

    }

    public static interface ProfileTaskReturning {

        public Object run() throws Throwable;

    }

    @JsonClass(exposeAll = true)
    public static record Profile(String name, double took_ms) {
    }

}
