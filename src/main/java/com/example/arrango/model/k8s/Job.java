package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;

/** batch/v1 Job. */
@Document("jobs")
public class Job extends K8sResource {

	private Integer completions;

	private Integer parallelism;

	private int active;

	private int succeeded;

	private int failed;

	/** {@code Complete}, {@code Failed}, {@code Suspended} oder {@code Running}, abgeleitet aus den Conditions. */
	private String status;

	private String startTime;

	private String completionTime;

	private List<String> images;

	public Job() {
	}

	public Job(String namespace, String name) {
		super("Job", namespace, name);
	}

	public Integer getCompletions() {
		return completions;
	}

	public void setCompletions(Integer completions) {
		this.completions = completions;
	}

	public Integer getParallelism() {
		return parallelism;
	}

	public void setParallelism(Integer parallelism) {
		this.parallelism = parallelism;
	}

	public int getActive() {
		return active;
	}

	public void setActive(int active) {
		this.active = active;
	}

	public int getSucceeded() {
		return succeeded;
	}

	public void setSucceeded(int succeeded) {
		this.succeeded = succeeded;
	}

	public int getFailed() {
		return failed;
	}

	public void setFailed(int failed) {
		this.failed = failed;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getStartTime() {
		return startTime;
	}

	public void setStartTime(String startTime) {
		this.startTime = startTime;
	}

	public String getCompletionTime() {
		return completionTime;
	}

	public void setCompletionTime(String completionTime) {
		this.completionTime = completionTime;
	}

	public List<String> getImages() {
		return images;
	}

	public void setImages(List<String> images) {
		this.images = images;
	}
}
