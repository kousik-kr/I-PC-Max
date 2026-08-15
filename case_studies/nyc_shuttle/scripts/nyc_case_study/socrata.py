"""Generic, checksummed Socrata downloader with verified pagination."""

from __future__ import annotations

import csv
import io
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterator

from .common import (
    CaseStudyError,
    artifact_metadata,
    atomic_write_json,
    canonical_json,
    immutable_write,
    utc_now,
)


Fetcher = Callable[[str, dict[str, str]], bytes]


def urllib_fetch(url: str, headers: dict[str, str]) -> bytes:
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


@dataclass(frozen=True)
class Page:
    offset: int
    url: str
    payload: bytes
    row_count: int


class SocrataClient:
    """Small Socrata client whose transport can be replaced in tests."""

    def __init__(self, domain: str, dataset_id: str, *, app_token_env: str | None = None,
                 fetcher: Fetcher = urllib_fetch, retries: int = 5,
                 backoff_seconds: float = 1.0) -> None:
        if not domain or "/" in domain or not dataset_id:
            raise ValueError("plain Socrata domain and dataset ID are required")
        self.domain = domain
        self.dataset_id = dataset_id
        self.fetcher = fetcher
        self.retries = retries
        self.backoff_seconds = backoff_seconds
        self.headers = {"User-Agent": "PACE-NYC-case-study/1"}
        if app_token_env and os.getenv(app_token_env):
            self.headers["X-App-Token"] = os.environ[app_token_env]

    def _fetch(self, url: str) -> bytes:
        last: Exception | None = None
        for attempt in range(self.retries):
            try:
                return self.fetcher(url, dict(self.headers))
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as failure:
                last = failure
                if attempt + 1 == self.retries:
                    break
                time.sleep(self.backoff_seconds * (2 ** attempt))
        raise CaseStudyError(f"failed after {self.retries} attempts: {url}: {last}") from last

    def metadata(self) -> tuple[str, dict[str, Any], bytes]:
        url = f"https://{self.domain}/api/views/{self.dataset_id}"
        payload = self._fetch(url)
        try:
            value = json.loads(payload)
        except json.JSONDecodeError as failure:
            raise CaseStudyError(f"Socrata metadata is not JSON: {url}") from failure
        if not isinstance(value, dict):
            raise CaseStudyError(f"Socrata metadata root is not an object: {url}")
        return url, value, payload

    def expected_count(self, where: str | None = None) -> tuple[str, int]:
        query = {"$select": "count(*)"}
        if where:
            query["$where"] = where
        url = self._resource_url("json", query)
        value = json.loads(self._fetch(url))
        if not isinstance(value, list) or len(value) != 1:
            raise CaseStudyError(f"unexpected Socrata count response: {url}")
        raw = value[0].get("count") if isinstance(value[0], dict) else None
        try:
            return url, int(raw)
        except (TypeError, ValueError) as failure:
            raise CaseStudyError(f"Socrata count response has no integer count: {url}") from failure

    def _resource_url(self, output_format: str, query: dict[str, Any]) -> str:
        encoded = urllib.parse.urlencode(query, doseq=True, safe=",():*")
        return f"https://{self.domain}/resource/{self.dataset_id}.{output_format}?{encoded}"

    def pages(self, *, output_format: str = "json", page_size: int = 50000,
              where: str | None = None, select: str | None = None,
              order: str = ":id") -> Iterator[Page]:
        if output_format not in {"json", "csv", "geojson"}:
            raise ValueError("format must be json, csv, or geojson")
        if page_size < 1:
            raise ValueError("page_size must be positive")
        offset = 0
        while True:
            query: dict[str, Any] = {"$limit": page_size, "$offset": offset, "$order": order}
            if where:
                query["$where"] = where
            if select:
                query["$select"] = select
            url = self._resource_url(output_format, query)
            payload = self._fetch(url)
            count = page_row_count(payload, output_format)
            yield Page(offset, url, payload, count)
            offset += count
            if count < page_size:
                break
            if count == 0:
                break


def page_row_count(payload: bytes, output_format: str) -> int:
    if output_format == "csv":
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8-sig"))))
        return max(0, len(rows) - 1)
    value = json.loads(payload)
    if output_format == "geojson":
        if not isinstance(value, dict) or not isinstance(value.get("features"), list):
            raise CaseStudyError("GeoJSON page is not a FeatureCollection")
        return len(value["features"])
    if not isinstance(value, list):
        raise CaseStudyError("JSON page is not an array")
    return len(value)


def download_dataset(client: SocrataClient, output_dir: Path, *, output_format: str,
                     page_size: int = 50000, where: str | None = None,
                     select: str | None = None) -> dict[str, Any]:
    """Download exact pages, metadata, and a verified manifest."""
    retrieved_at = utc_now()
    stamp = retrieved_at.replace(":", "").replace("-", "")
    destination = output_dir / f"{stamp}_{client.dataset_id}"
    metadata_url, metadata, metadata_payload = client.metadata()
    destination.mkdir(parents=True, exist_ok=True)
    metadata_path = destination / "schema.json"
    immutable_write(metadata_path, metadata_payload)
    count_url, expected = client.expected_count(where)

    artifacts: list[dict[str, Any]] = []
    observed = 0
    extension = {"json": "json", "csv": "csv", "geojson": "geojson"}[output_format]
    for index, page in enumerate(client.pages(
        output_format=output_format, page_size=page_size, where=where, select=select
    )):
        page_path = destination / f"page-{index:06d}-offset-{page.offset:012d}.{extension}"
        immutable_write(page_path, page.payload)
        observed += page.row_count
        item = artifact_metadata(
            page_path, url=page.url, dataset_id=client.dataset_id,
            retrieved_at=retrieved_at, schema={"format": output_format},
        )
        item["row_count"] = page.row_count
        item["offset"] = page.offset
        artifacts.append(item)

    if observed != expected:
        diagnostic = {
            "status": "FAILED_ROW_COUNT_MISMATCH",
            "expected_count": expected,
            "observed_count": observed,
            "count_url": count_url,
            "pages": artifacts,
        }
        atomic_write_json(destination / "FAILED_download_diagnostic.json", diagnostic)
        raise CaseStudyError(
            f"{client.dataset_id}: pagination would truncate or duplicate rows: "
            f"expected {expected}, downloaded {observed}; see {destination}"
        )

    columns = [
        {"name": column.get("name"), "fieldName": column.get("fieldName"),
         "dataTypeName": column.get("dataTypeName")}
        for column in metadata.get("columns", []) if isinstance(column, dict)
    ]
    manifest = {
        "schema_version": 1,
        "status": "COMPLETE",
        "dataset_id": client.dataset_id,
        "domain": client.domain,
        "format": output_format,
        "retrieved_at_utc": retrieved_at,
        "metadata_url": metadata_url,
        "count_url": count_url,
        "where": where,
        "select": select,
        "row_count": observed,
        "source_schema": columns,
        "metadata_artifact": artifact_metadata(
            metadata_path, url=metadata_url, dataset_id=client.dataset_id,
            retrieved_at=retrieved_at, schema={"format": "socrata_view_metadata"},
        ),
        "pages": artifacts,
        "manifest_content_contract": "exact raw page bytes plus Socrata view metadata",
    }
    manifest["content_checksum"] = __import__("hashlib").sha256(
        canonical_json([(item["sha256"], item["row_count"]) for item in artifacts]).encode()
    ).hexdigest()
    atomic_write_json(destination / "download_manifest.json", manifest)
    return manifest


def download_geospatial_export(client: SocrataClient, output_dir: Path) -> dict[str, Any]:
    """Download an official Socrata geospatial export when SODA rows are federated stubs."""
    retrieved_at = utc_now()
    stamp = retrieved_at.replace(":", "").replace("-", "")
    destination = output_dir / f"{stamp}_{client.dataset_id}"
    destination.mkdir(parents=True, exist_ok=True)
    metadata_url, metadata, metadata_payload = client.metadata()
    metadata_path = destination / "schema.json"
    immutable_write(metadata_path, metadata_payload)
    count_url, expected = client.expected_count()
    url = (
        f"https://{client.domain}/api/geospatial/{client.dataset_id}"
        "?method=export&format=GeoJSON"
    )
    payload = client._fetch(url)
    try:
        value = json.loads(payload)
    except json.JSONDecodeError as failure:
        raise CaseStudyError(f"geospatial export is not JSON: {url}") from failure
    features = value.get("features") if isinstance(value, dict) else None
    if not isinstance(features, list):
        raise CaseStudyError(f"geospatial export is not a FeatureCollection: {url}")
    observed = len(features)
    if observed != expected:
        raise CaseStudyError(
            f"{client.dataset_id}: geospatial export row mismatch: expected {expected}, got {observed}"
        )
    if observed and not any(item.get("geometry") for item in features if isinstance(item, dict)):
        raise CaseStudyError(f"{client.dataset_id}: official geospatial export contains no geometry")
    path = destination / "page-000000-offset-000000000000.geojson"
    immutable_write(path, payload)
    properties = next(
        (item.get("properties", {}) for item in features
         if isinstance(item, dict) and isinstance(item.get("properties"), dict)),
        {},
    )
    page = artifact_metadata(
        path, url=url, dataset_id=client.dataset_id, retrieved_at=retrieved_at,
        schema={"format": "geojson", "property_fields": sorted(properties)},
    )
    page.update({"row_count": observed, "offset": 0})
    manifest = {
        "schema_version": 1, "status": "COMPLETE",
        "dataset_id": client.dataset_id, "domain": client.domain,
        "format": "geojson", "retrieved_at_utc": retrieved_at,
        "metadata_url": metadata_url, "count_url": count_url,
        "row_count": observed,
        "source_schema": [{"fieldName": key, "inferred_from_export": True}
                          for key in sorted(properties)],
        "metadata_artifact": artifact_metadata(
            metadata_path, url=metadata_url, dataset_id=client.dataset_id,
            retrieved_at=retrieved_at, schema={"format": "socrata_view_metadata"},
        ),
        "pages": [page],
        "manifest_content_contract": "exact official Socrata geospatial export bytes",
        "content_checksum": page["sha256"],
    }
    atomic_write_json(destination / "download_manifest.json", manifest)
    return manifest
