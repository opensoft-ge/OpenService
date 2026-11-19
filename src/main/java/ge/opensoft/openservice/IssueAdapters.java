package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;

final class IssueAdapters {
    private IssueAdapters() {}

    static final Map<String,String> CATALOG = Map.of(
            "github", "/repos/{owner}/{repo}/issues/{number}",
            "gitlab", "/api/v4/projects/{numeric_project_id}/issues/{iid}");

    static void validatePath(String adapter, String path) {
        require(CATALOG.containsKey(adapter), "Choose a catalog adapter");
        String pattern = adapter.equals("github")
                ? "/repos/[A-Za-z0-9_-]+/(?![.]{1,2}/)[A-Za-z0-9_.-]+/issues/[1-9][0-9]*"
                : "/api/v4/projects/[1-9][0-9]*/issues/[1-9][0-9]*";
        require(path.matches(pattern), "Use the issue path shown in the catalog");
    }

    static Map<String,Object> source(String adapter, String path, Map<String,Object> issue,
                                     String target) {
        String numberField;
        String descriptionField;
        if (adapter.equals("github")) {
            require(!issue.containsKey("pull_request"), "Choose a GitHub issue");
            numberField = "number";
            descriptionField = "body";
        } else {
            require(path.split("/")[4].equals(Objects.toString(issue.get("project_id"))),
                    "GitLab project does not match the requested path");
            numberField = "iid";
            descriptionField = "description";
        }
        require(path.substring(path.lastIndexOf('/') + 1).equals(Objects.toString(issue.get(numberField))),
                "Issue number does not match the requested path");
        Object description = issue.get(descriptionField);
        require(description == null || description instanceof String, "Issue description must be text");
        return map("record_id", target, "version", WorkJobs.text(issue.get("updated_at"), 160),
                "id", WorkJobs.text(Objects.toString(issue.get("id"), ""), 80),
                "values", map("short_description", WorkJobs.text(issue.get("title"), 160),
                        "description", Objects.toString(description, "")));
    }
}
