package com.mypropertyfact.estate.services;

import org.springframework.http.HttpMethod;

/**
 * Maps admin API method + path to a short human-readable task label for audit logs.
 */
public final class AdminApiTaskDescriber {

    private AdminApiTaskDescriber() {
    }

    public static String describe(String httpMethod, String requestPath) {
        if (requestPath == null) {
            return "Admin action";
        }
        String path = requestPath;
        String method = httpMethod != null ? httpMethod.toUpperCase() : "";

        if (path.contains("/dashboard-stats")) {
            return "View dashboard statistics";
        }
        if (path.contains("/dashboard/my-activity")) {
            return "View recent dashboard activity";
        }
        if (path.contains("/dashboard/site-traffic-trends")) {
            return "View website traffic trend chart";
        }
        if (path.contains("/dashboard/site-traffic-live")) {
            return "View live website traffic chart";
        }
        if (path.contains("/dashboard/site-traffic-today")) {
            return "View today 24h website traffic";
        }
        if (path.contains("/api/v1/blog-category")) {
            if (path.contains("/add-update") && HttpMethod.POST.matches(method)) {
                return "Create or update blog category";
            }
            if (path.contains("/delete/") && HttpMethod.DELETE.matches(method)) {
                return "Delete blog category";
            }
            if (path.contains("/get-all")) {
                return "List blog categories";
            }
            if (path.contains("/get/")) {
                return "Open blog category";
            }
            return "Blog category API";
        }
        if (path.contains("/api/v1/blog")) {
            if (path.contains("/add-update") && HttpMethod.POST.matches(method)) {
                return "Create or update blog post";
            }
            if (path.matches("/api/v1/blog/\\d+")) {
                if (HttpMethod.DELETE.matches(method)) {
                    return "Delete blog post";
                }
                if (HttpMethod.GET.matches(method)) {
                    return "Open blog post by id";
                }
            }
            if (path.contains("/get-all")) {
                return "List blog posts";
            }
            if (path.contains("/get/")) {
                return "Load blog post by slug";
            }
            if (path.equals("/api/v1/blog/get")) {
                return "List or search blog posts (paginated)";
            }
            return "Blog API";
        }
        if (path.contains("/api/v1/enquiry")) {
            if (HttpMethod.POST.matches(method)) {
                return "Submit or update enquiry";
            }
            if (HttpMethod.DELETE.matches(method)) {
                return "Delete enquiry";
            }
            if (path.contains("/update-status")) {
                return "Update enquiry status";
            }
            if (HttpMethod.GET.matches(method)) {
                return "List or view enquiries";
            }
            return "Enquiry API";
        }
        if (path.contains("/api/v1/projects")) {
            if (path.contains("/add-new") && HttpMethod.POST.matches(method)) {
                return "Create or update project"; 
            }
            if (path.contains("/delete/") && HttpMethod.DELETE.matches(method)) {
                return "Delete project";
            }
            if (path.contains("/admin-export")) {
                return "Export projects for admin / Excel";
            }
            if (path.contains("/get/") && HttpMethod.GET.matches(method)) {
                return "Load project details by slug";
            }
            if (path.equals("/api/v1/projects")) {
                return "List projects (summary)";
            }
            if (path.contains("/add-update-amenity")) {
                return "Create or update project amenity";
            }
            if (path.contains("/search-by-type-city-budget")) {
                return "Search projects by filters";
            }
            return "Project API";
        }
        if (path.contains("/api/v1/excel-upload")) {
            return "Excel bulk upload (projects or data)";
        }
        if (path.contains("/api/v1/admin/property-listings")) {
            if (HttpMethod.POST.matches(method)) {
                return "Submit or update property listing (admin)";
            }
            if (path.matches(".*/property-listings/\\d+.*")) {
                return HttpMethod.GET.matches(method)
                        ? "Open property listing details"
                        : "Update property listing status or details";
            }
            return "Browse or load property listings";
        }
        if (path.contains("/api/v1/admin/roles")) {
            if (HttpMethod.GET.matches(method)) {
                return path.matches(".*/roles/\\d+.*") ? "View role details" : "List roles";
            }
            if (HttpMethod.POST.matches(method)) {
                return "Create or update role";
            }
            if (HttpMethod.PUT.matches(method) || HttpMethod.PATCH.matches(method)) {
                return "Update role";
            }
            if (HttpMethod.DELETE.matches(method)) {
                return "Delete role";
            }
            return "Role management";
        }
        if (path.contains("/api/v1/admin/super/traffic")) {
            if (path.contains("/traffic/visits-export")) {
                return "Download traffic CSV export";
            }
            return "View live traffic summary";
        }
        if (path.contains("/api/v1/admin/super/search-reports")) {
            if (path.contains("/export")) {
                return "Download search report Excel";
            }
            return "View search reports";
        }
        if (path.contains("/api/v1/admin/super/audit-logs")) {
            return "View admin audit logs";
        }
        if (path.contains("/api/v1/admin/super/live-listings")) {
            return "View live website and portal listings";
        }
        if (path.contains("/api/v1/admin/super/project-listings/averages")) {
            return "View average project listings per person";
        }
        if (path.contains("/api/v1/admin/super/project-listings")) {
            if (HttpMethod.DELETE.matches(method)) {
                return "Delete project listing from activity dashboard";
            }
            return "View project listing activity dashboard";
        }
        if (path.contains("/api/v1/admin/super/portal-listing-stats")) {
            return "View portal listing counts";
        }
        if (path.contains("/api/v1/admin/management/activities")) {
            return "View management activity log";
        }

        return fallbackVerb(method, path);
    }

    private static String fallbackVerb(String method, String path) {
        int last = path.lastIndexOf('/');
        String tail = last >= 0 && last < path.length() - 1 ? path.substring(last + 1) : path;
        tail = tail.replaceAll("[?#].*$", "");
        if (tail.isBlank()) {
            tail = "resource";
        }
        String title = slugToTitle(tail);
        if (HttpMethod.GET.matches(method)) {
            return "View " + title;
        }
        if (HttpMethod.POST.matches(method)) {
            return "Create or submit " + title;
        }
        if (HttpMethod.PUT.matches(method) || HttpMethod.PATCH.matches(method)) {
            return "Update " + title;
        }
        if (HttpMethod.DELETE.matches(method)) {
            return "Delete " + title;
        }
        return method + " " + title;
    }

    private static String slugToTitle(String slug) {
        String s = slug.replace('-', ' ').replace('_', ' ').trim();
        if (s.isEmpty()) {
            return "item";
        }
        String[] parts = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
