package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;

/** batch/v1 CronJob. */
@Document("cron_jobs")
public class CronJob extends K8sResource {

	private String schedule;

	private String timeZone;

	private boolean suspend;

	private String lastScheduleTime;

	private String lastSuccessfulTime;

	private int activeJobs;

	private List<String> images;

	public CronJob() {
	}

	public CronJob(String namespace, String name) {
		super("CronJob", namespace, name);
	}

	public String getSchedule() {
		return schedule;
	}

	public void setSchedule(String schedule) {
		this.schedule = schedule;
	}

	public String getTimeZone() {
		return timeZone;
	}

	public void setTimeZone(String timeZone) {
		this.timeZone = timeZone;
	}

	public boolean isSuspend() {
		return suspend;
	}

	public void setSuspend(boolean suspend) {
		this.suspend = suspend;
	}

	public String getLastScheduleTime() {
		return lastScheduleTime;
	}

	public void setLastScheduleTime(String lastScheduleTime) {
		this.lastScheduleTime = lastScheduleTime;
	}

	public String getLastSuccessfulTime() {
		return lastSuccessfulTime;
	}

	public void setLastSuccessfulTime(String lastSuccessfulTime) {
		this.lastSuccessfulTime = lastSuccessfulTime;
	}

	public int getActiveJobs() {
		return activeJobs;
	}

	public void setActiveJobs(int activeJobs) {
		this.activeJobs = activeJobs;
	}

	public List<String> getImages() {
		return images;
	}

	public void setImages(List<String> images) {
		this.images = images;
	}
}
