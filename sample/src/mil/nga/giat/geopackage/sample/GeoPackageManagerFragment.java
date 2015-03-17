package mil.nga.giat.geopackage.sample;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import mil.nga.giat.geopackage.BoundingBox;
import mil.nga.giat.geopackage.GeoPackage;
import mil.nga.giat.geopackage.GeoPackageConstants;
import mil.nga.giat.geopackage.GeoPackageException;
import mil.nga.giat.geopackage.GeoPackageManager;
import mil.nga.giat.geopackage.core.contents.Contents;
import mil.nga.giat.geopackage.core.contents.ContentsDao;
import mil.nga.giat.geopackage.core.srs.SpatialReferenceSystem;
import mil.nga.giat.geopackage.core.srs.SpatialReferenceSystemDao;
import mil.nga.giat.geopackage.factory.GeoPackageFactory;
import mil.nga.giat.geopackage.features.columns.GeometryColumns;
import mil.nga.giat.geopackage.features.columns.GeometryColumnsDao;
import mil.nga.giat.geopackage.features.user.FeatureDao;
import mil.nga.giat.geopackage.geom.GeometryType;
import mil.nga.giat.geopackage.io.GeoPackageIOUtils;
import mil.nga.giat.geopackage.io.GeoPackageProgress;
import mil.nga.giat.geopackage.schema.TableColumnKey;
import mil.nga.giat.geopackage.tiles.matrix.TileMatrix;
import mil.nga.giat.geopackage.tiles.matrixset.TileMatrixSet;
import mil.nga.giat.geopackage.tiles.matrixset.TileMatrixSetDao;
import mil.nga.giat.geopackage.tiles.user.TileDao;
import mil.nga.giat.geopackage.user.UserColumn;
import mil.nga.giat.geopackage.user.UserTable;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap.CompressFormat;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.DocumentsContract.Document;
import android.support.v4.content.FileProvider;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import com.ipaulpro.afilechooser.utils.FileUtils;

/**
 * Manager Fragment, import, view, select, edit GeoPackages
 * 
 * @author osbornb
 * 
 */
public class GeoPackageManagerFragment extends Fragment implements
		ILoadTilesTask {

	/**
	 * Get a new fragment instance
	 * 
	 * @return
	 */
	public static GeoPackageManagerFragment newInstance() {
		GeoPackageManagerFragment listFragment = new GeoPackageManagerFragment();
		return listFragment;
	}

	/**
	 * Active GeoPackages
	 */
	private GeoPackageDatabases active;

	/**
	 * Expandable list adapter
	 */
	private GeoPackageListAdapter adapter = new GeoPackageListAdapter();

	/**
	 * List of databases
	 */
	private List<String> databases = new ArrayList<String>();

	/**
	 * List of database tables within each database
	 */
	private List<List<GeoPackageTable>> databaseTables = new ArrayList<List<GeoPackageTable>>();

	/**
	 * Layout inflater
	 */
	private LayoutInflater inflater;

	/**
	 * GeoPackage manager
	 */
	private GeoPackageManager manager;

	/**
	 * Progress dialog for network operations
	 */
	private ProgressDialog progressDialog;

	/**
	 * Constructor
	 */
	public GeoPackageManagerFragment() {

	}

	/**
	 * Constructor
	 * 
	 * @param active
	 */
	public void setActive(GeoPackageDatabases active) {
		this.active = active;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		this.inflater = inflater;
		manager = GeoPackageFactory.getManager(getActivity());
		View v = inflater.inflate(R.layout.fragment_manager, null);
		ExpandableListView elv = (ExpandableListView) v
				.findViewById(R.id.fragment_manager_view_ui);
		elv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view,
					int position, long id) {
				int itemType = ExpandableListView.getPackedPositionType(id);
				if (itemType == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
					int childPosition = ExpandableListView
							.getPackedPositionChild(id);
					int groupPosition = ExpandableListView
							.getPackedPositionGroup(id);
					tableOptions(databaseTables.get(groupPosition).get(
							childPosition));
					return true;
				} else if (itemType == ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
					int groupPosition = ExpandableListView
							.getPackedPositionGroup(id);
					databaseOptions(databases.get(groupPosition));
					return true;
				}
				return false;
			}
		});
		elv.setAdapter(adapter);

		return v;
	}

	@Override
	public void onResume() {
		super.onResume();
		update();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);

		if (!hidden && active.isModified()) {
			active.setModified(false);
			update();
		}
	}

	/**
	 * Update the listing of databases and tables
	 */
	public void update() {
		databases = manager.databases();
		databaseTables.clear();
		for (String database : databases) {
			GeoPackage geoPackage = manager.open(database);
			List<GeoPackageTable> tables = new ArrayList<GeoPackageTable>();
			ContentsDao contentsDao = geoPackage.getContentsDao();
			for (String tableName : geoPackage.getFeatureTables()) {
				FeatureDao featureDao = geoPackage.getFeatureDao(tableName);
				int count = featureDao.count();

				GeometryType geometryType = null;
				try {
					Contents contents = contentsDao.queryForId(tableName);
					GeometryColumns geometryColumns = contents
							.getGeometryColumns();
					geometryType = geometryColumns.getGeometryType();
				} catch (Exception e) {
				}

				GeoPackageTable table = GeoPackageTable.createFeature(database,
						tableName, geometryType, count);
				table.setActive(active.exists(table));
				tables.add(table);
			}
			for (String tableName : geoPackage.getTileTables()) {
				TileDao tileDao = geoPackage.getTileDao(tableName);
				int count = tileDao.count();
				GeoPackageTable table = GeoPackageTable.createTile(database,
						tableName, count);
				table.setActive(active.exists(table));
				tables.add(table);
			}
			databaseTables.add(tables);
			geoPackage.close();
		}

		adapter.notifyDataSetChanged();
	}

	/**
	 * Show options for the GeoPackage database
	 * 
	 * @param database
	 */
	private void databaseOptions(final String database) {

		ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(),
				android.R.layout.select_dialog_item);
		adapter.add(getString(R.string.geopackage_view_label));
		adapter.add(getString(R.string.geopackage_delete_label));
		adapter.add(getString(R.string.geopackage_rename_label));
		adapter.add(getString(R.string.geopackage_copy_label));
		adapter.add(getString(R.string.geopackage_export_label));
		adapter.add(getString(R.string.geopackage_share_label));
		adapter.add(getString(R.string.geopackage_create_features_label));
		adapter.add(getString(R.string.geopackage_create_tiles_label));
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(database);
		builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int item) {

				if (item >= 0) {

					switch (item) {
					case 0:
						viewDatabaseOption(database);
						break;
					case 1:
						deleteDatabaseOption(database);
						break;
					case 2:
						renameDatabaseOption(database);
						break;
					case 3:
						copyDatabaseOption(database);
						break;
					case 4:
						exportDatabaseOption(database);
						break;
					case 5:
						shareDatabaseOption(database);
						break;
					case 6:
						createFeaturesOption(database);
						break;
					case 7:
						createTilesOption(database);
						break;
					default:
					}
				}
			}
		});

		AlertDialog alert = builder.create();
		alert.show();
	}

	/**
	 * View database information
	 * 
	 * @param database
	 */
	private void viewDatabaseOption(final String database) {
		StringBuilder databaseInfo = new StringBuilder();
		GeoPackage geoPackage = manager.open(database);
		try {
			SpatialReferenceSystemDao srsDao = geoPackage
					.getSpatialReferenceSystemDao();

			List<SpatialReferenceSystem> srsList = srsDao.queryForAll();
			databaseInfo.append("Size: ")
					.append(manager.readableSize(database));
			databaseInfo.append("\n\nLocation: ").append(
					manager.isExternal(database) ? "External" : "Local");
			databaseInfo.append("\nPath: ").append(manager.getPath(database));
			databaseInfo.append("\n\nFeature Tables: ").append(
					geoPackage.getFeatureTables().size());
			databaseInfo.append("\nTile Tables: ").append(
					geoPackage.getTileTables().size());
			databaseInfo.append("\n\nSpatial Reference Systems: ").append(
					srsList.size());
			for (SpatialReferenceSystem srs : srsList) {
				databaseInfo.append("\n");
				addSrs(databaseInfo, srs);
			}

		} catch (SQLException e) {
			databaseInfo.append(e.getMessage());
		} finally {
			geoPackage.close();
		}
		AlertDialog viewDialog = new AlertDialog.Builder(getActivity())
				.setTitle(database)
				.setPositiveButton(getString(R.string.button_ok_label),

				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				}).setMessage(databaseInfo.toString()).create();
		viewDialog.show();
	}

	/**
	 * Add Spatial Reference System to the info
	 * 
	 * @param info
	 * @param srs
	 */
	private void addSrs(StringBuilder info, SpatialReferenceSystem srs) {
		info.append("\nSRS Name: ").append(srs.getSrsName());
		info.append("\nSRS ID: ").append(srs.getSrsId());
		info.append("\nOrganization: ").append(srs.getOrganization());
		info.append("\nCoordsys ID: ").append(srs.getOrganizationCoordsysId());
		info.append("\nDefinition: ").append(srs.getDefinition());
		info.append("\nDescription: ").append(srs.getDescription());
	}

	/**
	 * Delete database alert option
	 * 
	 * @param database
	 */
	private void deleteDatabaseOption(final String database) {
		AlertDialog deleteDialog = new AlertDialog.Builder(getActivity())
				.setTitle(getString(R.string.geopackage_delete_label))
				.setMessage(
						getString(R.string.geopackage_delete_label) + " "
								+ database + "?")
				.setPositiveButton(getString(R.string.geopackage_delete_label),

				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {

						manager.delete(database);
						active.removeDatabase(database);
						update();
					}
				})

				.setNegativeButton(getString(R.string.button_cancel_label),
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int which) {
								dialog.dismiss();
							}
						}).create();
		deleteDialog.show();
	}

	/**
	 * Rename database option
	 * 
	 * @param database
	 */
	private void renameDatabaseOption(final String database) {

		final EditText input = new EditText(getActivity());
		input.setText(database);

		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity())
				.setTitle(getString(R.string.geopackage_rename_label))
				.setView(input)
				.setPositiveButton(getString(R.string.button_ok_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								String value = input.getText().toString();
								if (value != null && !value.isEmpty()
										&& !value.equals(database)) {
									try {
										if (manager.rename(database, value)) {
											active.renameDatabase(database,
													value);
											update();
										} else {
											GeoPackageUtils
													.showMessage(
															getActivity(),
															getString(R.string.geopackage_rename_label),
															"Rename from "
																	+ database
																	+ " to "
																	+ value
																	+ " was not successful");
										}
									} catch (Exception e) {
										GeoPackageUtils
												.showMessage(
														getActivity(),
														getString(R.string.geopackage_rename_label),
														e.getMessage());
									}
								}
							}
						})
				.setNegativeButton(getString(R.string.button_cancel_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								dialog.cancel();
							}
						});

		dialog.show();
	}

	/**
	 * Copy database option
	 * 
	 * @param database
	 */
	private void copyDatabaseOption(final String database) {

		final EditText input = new EditText(getActivity());
		input.setText(database + getString(R.string.geopackage_copy_suffix));

		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity())
				.setTitle(getString(R.string.geopackage_copy_label))
				.setView(input)
				.setPositiveButton(getString(R.string.button_ok_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								String value = input.getText().toString();
								if (value != null && !value.isEmpty()
										&& !value.equals(database)) {
									try {
										if (manager.copy(database, value)) {
											update();
										} else {
											GeoPackageUtils
													.showMessage(
															getActivity(),
															getString(R.string.geopackage_copy_label),
															"Copy from "
																	+ database
																	+ " to "
																	+ value
																	+ " was not successful");
										}
									} catch (Exception e) {
										GeoPackageUtils
												.showMessage(
														getActivity(),
														getString(R.string.geopackage_copy_label),
														e.getMessage());
									}
								}
							}
						})
				.setNegativeButton(getString(R.string.button_cancel_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								dialog.cancel();
							}
						});

		dialog.show();
	}

	/**
	 * Export database option
	 * 
	 * @param database
	 */
	private void exportDatabaseOption(final String database) {

		final File directory = Environment.getExternalStorageDirectory();
		final EditText input = new EditText(getActivity());
		input.setText(database);

		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity())
				.setTitle(getString(R.string.geopackage_export_label))
				.setMessage(directory.getPath() + File.separator)
				.setView(input)
				.setPositiveButton(getString(R.string.button_ok_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								String value = input.getText().toString();
								if (value != null && !value.isEmpty()) {
									try {
										manager.exportGeoPackage(database,
												value, directory);
									} catch (Exception e) {
										GeoPackageUtils
												.showMessage(
														getActivity(),
														getString(R.string.geopackage_export_label),
														e.getMessage());
									}
								}
							}
						})
				.setNegativeButton(getString(R.string.button_cancel_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								dialog.cancel();
							}
						});

		dialog.show();
	}

	/**
	 * Share database option
	 * 
	 * @param database
	 */
	private void shareDatabaseOption(final String database) {

		try {
			// Get the database file
			File databaseFile = manager.getFile(database);

			// Create the share intent
			Intent shareIntent = new Intent();
			shareIntent.setAction(Intent.ACTION_SEND);
			shareIntent.setType("*/*");

			Uri databaseUri;
			// If external database, no permission is needed and Uri is created
			if (manager.isExternal(database)) {
				databaseUri = Uri.fromFile(databaseFile);
			}
			// If internal database, file much be copied to cache and permission
			// granted
			else {
				// Copy the database to cache
				File cacheDirectory = getDatabaseCacheDirectory();
				cacheDirectory.mkdir();
				File cacheFile = new File(cacheDirectory, database + "."
						+ GeoPackageConstants.GEOPACKAGE_EXTENSION);
				try {
					GeoPackageIOUtils.copyFile(databaseFile, cacheFile);
				} catch (IOException e) {
					throw new GeoPackageException(
							"Failed to copy database file to cache for sharing",
							e);
				}
				// Create the content Uri and add intent permissions
				databaseUri = FileProvider.getUriForFile(getActivity(),
						"mil.nga.giat.geopackage.sample.MainActivity",
						cacheFile);
				shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			}

			// Add the Uri
			shareIntent.putExtra(Intent.EXTRA_STREAM, databaseUri);

			// Start the share activity for result to delete the cache when done
			startActivityForResult(
					Intent.createChooser(
							shareIntent,
							getResources().getText(
									R.string.geopackage_share_label)),
					MainActivity.ACTIVITY_SHARE_FILE);
		} catch (Exception e) {
			GeoPackageUtils.showMessage(getActivity(),
					getString(R.string.geopackage_share_label), e.getMessage());
		}
	}

	/**
	 * Get the database cache directory
	 * 
	 * @return
	 */
	private File getDatabaseCacheDirectory() {
		return new File(getActivity().getCacheDir(), "databases");
	}

	/**
	 * Create features option
	 * 
	 * @param database
	 */
	private void createFeaturesOption(final String database) {

		LayoutInflater inflater = LayoutInflater.from(getActivity());
		View createFeaturesView = inflater.inflate(R.layout.create_features,
				null);
		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
		dialog.setView(createFeaturesView);

		final EditText nameInput = (EditText) createFeaturesView
				.findViewById(R.id.create_features_name_input);
		final EditText minLatInput = (EditText) createFeaturesView
				.findViewById(R.id.bounding_box_min_latitude_input);
		final EditText maxLatInput = (EditText) createFeaturesView
				.findViewById(R.id.bounding_box_max_latitude_input);
		final EditText minLonInput = (EditText) createFeaturesView
				.findViewById(R.id.bounding_box_min_longitude_input);
		final EditText maxLonInput = (EditText) createFeaturesView
				.findViewById(R.id.bounding_box_max_longitude_input);
		final Button preloadedLocationsButton = (Button) createFeaturesView
				.findViewById(R.id.bounding_box_preloaded);
		final Spinner geometryTypeSpinner = (Spinner) createFeaturesView
				.findViewById(R.id.create_features_geometry_type);

		GeoPackageUtils
				.prepareBoundingBoxInputs(getActivity(), minLatInput,
						maxLatInput, minLonInput, maxLonInput,
						preloadedLocationsButton);

		dialog.setPositiveButton(
				getString(R.string.geopackage_create_features_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {

						try {

							String tableName = nameInput.getText().toString();
							if (tableName == null || tableName.isEmpty()) {
								throw new GeoPackageException(
										getString(R.string.create_features_name_label)
												+ " is required");
							}
							double minLat = Double.valueOf(minLatInput
									.getText().toString());
							double maxLat = Double.valueOf(maxLatInput
									.getText().toString());
							double minLon = Double.valueOf(minLonInput
									.getText().toString());
							double maxLon = Double.valueOf(maxLonInput
									.getText().toString());

							if (minLat > maxLat) {
								throw new GeoPackageException(
										getString(R.string.bounding_box_min_latitude_label)
												+ " can not be larger than "
												+ getString(R.string.bounding_box_max_latitude_label));
							}

							if (minLon > maxLon) {
								throw new GeoPackageException(
										getString(R.string.bounding_box_min_longitude_label)
												+ " can not be larger than "
												+ getString(R.string.bounding_box_max_longitude_label));
							}

							BoundingBox boundingBox = new BoundingBox(minLon,
									maxLon, minLat, maxLat);

							GeometryType geometryType = GeometryType
									.fromName(geometryTypeSpinner
											.getSelectedItem().toString());

							GeometryColumns geometryColumns = new GeometryColumns();
							geometryColumns.setId(new TableColumnKey(tableName,
									"geom"));
							geometryColumns.setGeometryType(geometryType);
							geometryColumns.setZ((byte) 0);
							geometryColumns.setM((byte) 0);

							GeoPackage geoPackage = manager.open(database);
							try {
								geoPackage.createFeatureTableWithMetadata(
										geometryColumns, boundingBox, 4326);
							} finally {
								geoPackage.close();
							}
							update();

						} catch (Exception e) {
							GeoPackageUtils
									.showMessage(
											getActivity(),
											getString(R.string.geopackage_create_features_label),
											e.getMessage());
						}
					}
				}).setNegativeButton(getString(R.string.button_cancel_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
					}
				});
		dialog.show();
	}

	/**
	 * Create tiles option
	 * 
	 * @param database
	 */
	private void createTilesOption(final String database) {

		LayoutInflater inflater = LayoutInflater.from(getActivity());
		View createTilesView = inflater.inflate(R.layout.create_tiles, null);
		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
		dialog.setView(createTilesView);

		final EditText nameInput = (EditText) createTilesView
				.findViewById(R.id.create_tiles_name_input);
		final EditText urlInput = (EditText) createTilesView
				.findViewById(R.id.load_tiles_url_input);
		final Button preloadedUrlsButton = (Button) createTilesView
				.findViewById(R.id.load_tiles_preloaded);
		final EditText minZoomInput = (EditText) createTilesView
				.findViewById(R.id.load_tiles_min_zoom_input);
		final EditText maxZoomInput = (EditText) createTilesView
				.findViewById(R.id.load_tiles_max_zoom_input);
		final Spinner compressFormatInput = (Spinner) createTilesView
				.findViewById(R.id.load_tiles_compress_format);
		final EditText compressQualityInput = (EditText) createTilesView
				.findViewById(R.id.load_tiles_compress_quality);
		final RadioButton googleTilesRadioButton = (RadioButton) createTilesView
				.findViewById(R.id.load_tiles_type_google_radio_button);
		final EditText minLatInput = (EditText) createTilesView
				.findViewById(R.id.bounding_box_min_latitude_input);
		final EditText maxLatInput = (EditText) createTilesView
				.findViewById(R.id.bounding_box_max_latitude_input);
		final EditText minLonInput = (EditText) createTilesView
				.findViewById(R.id.bounding_box_min_longitude_input);
		final EditText maxLonInput = (EditText) createTilesView
				.findViewById(R.id.bounding_box_max_longitude_input);
		final Button preloadedLocationsButton = (Button) createTilesView
				.findViewById(R.id.bounding_box_preloaded);

		GeoPackageUtils
				.prepareBoundingBoxInputs(getActivity(), minLatInput,
						maxLatInput, minLonInput, maxLonInput,
						preloadedLocationsButton);

		GeoPackageUtils.prepareTileLoadInputs(getActivity(), minZoomInput,
				maxZoomInput, preloadedUrlsButton, nameInput, urlInput,
				compressFormatInput, compressQualityInput);

		dialog.setPositiveButton(
				getString(R.string.geopackage_create_tiles_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {

						try {

							String tableName = nameInput.getText().toString();
							if (tableName == null || tableName.isEmpty()) {
								throw new GeoPackageException(
										getString(R.string.create_tiles_name_label)
												+ " is required");
							}
							String tileUrl = urlInput.getText().toString();
							int minZoom = Integer.valueOf(minZoomInput
									.getText().toString());
							int maxZoom = Integer.valueOf(maxZoomInput
									.getText().toString());
							double minLat = Double.valueOf(minLatInput
									.getText().toString());
							double maxLat = Double.valueOf(maxLatInput
									.getText().toString());
							double minLon = Double.valueOf(minLonInput
									.getText().toString());
							double maxLon = Double.valueOf(maxLonInput
									.getText().toString());

							if (minLat > maxLat) {
								throw new GeoPackageException(
										getString(R.string.bounding_box_min_latitude_label)
												+ " can not be larger than "
												+ getString(R.string.bounding_box_max_latitude_label));
							}

							if (minLon > maxLon) {
								throw new GeoPackageException(
										getString(R.string.bounding_box_min_longitude_label)
												+ " can not be larger than "
												+ getString(R.string.bounding_box_max_longitude_label));
							}

							CompressFormat compressFormat = null;
							Integer compressQuality = null;
							if (compressFormatInput.getSelectedItemPosition() > 0) {
								compressFormat = CompressFormat
										.valueOf(compressFormatInput
												.getSelectedItem().toString());
								compressQuality = Integer
										.valueOf(compressQualityInput.getText()
												.toString());
							}

							boolean googleTiles = googleTilesRadioButton
									.isChecked();

							BoundingBox boundingBox = new BoundingBox(minLon,
									maxLon, minLat, maxLat);

							// If not importing tiles, just create the table
							if (tileUrl == null || tileUrl.isEmpty()) {
								GeoPackage geoPackage = manager.open(database);
								try {
									geoPackage.createTileTableWithMetadata(
											tableName, boundingBox,
											boundingBox, 4326);
								} finally {
									geoPackage.close();
								}
								update();
							} else {
								// Load tiles
								LoadTilesTask.loadTiles(getActivity(),
										GeoPackageManagerFragment.this, active,
										database, tableName, tileUrl, minZoom,
										maxZoom, compressFormat,
										compressQuality, googleTiles,
										boundingBox);
							}
						} catch (Exception e) {
							GeoPackageUtils
									.showMessage(
											getActivity(),
											getString(R.string.geopackage_create_tiles_label),
											e.getMessage());
						}
					}
				}).setNegativeButton(getString(R.string.button_cancel_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
					}
				});
		dialog.show();

	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onLoadTilesCancelled(String result) {
		update();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onLoadTilesPostExecute(String result) {
		if (result != null) {
			GeoPackageUtils.showMessage(getActivity(),
					getString(R.string.geopackage_create_tiles_label), result);
		}
		update();
	}

	/**
	 * Show options for the GeoPackage table
	 * 
	 * @param table
	 */
	private void tableOptions(final GeoPackageTable table) {

		ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(),
				android.R.layout.select_dialog_item);
		adapter.add(getString(R.string.geopackage_table_view_label));
		adapter.add(getString(R.string.geopackage_table_edit_label));
		adapter.add(getString(R.string.geopackage_table_delete_label));
		if (table.isTile()) {
			adapter.add(getString(R.string.geopackage_table_tiles_load_label));
		}
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(table.getDatabase() + " - " + table.getName());
		builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int item) {

				if (item >= 0) {

					switch (item) {
					case 0:
						viewTableOption(table);
						break;
					case 1:
						editTableOption(table);
						break;
					case 2:
						deleteTableOption(table);
						break;
					case 3:
						if (table.isTile()) {
							loadTilesTableOption(table);
						}
						break;

					default:
					}
				}
			}
		});

		AlertDialog alert = builder.create();
		alert.show();
	}

	/**
	 * View table information
	 * 
	 * @param table
	 */
	private void viewTableOption(final GeoPackageTable table) {
		StringBuilder info = new StringBuilder();
		GeoPackage geoPackage = manager.open(table.getDatabase());
		try {
			Contents contents = null;
			FeatureDao featureDao = null;
			TileDao tileDao = null;
			UserTable<? extends UserColumn> userTable = null;
			if (table.isFeature()) {
				featureDao = geoPackage.getFeatureDao(table.getName());
				contents = featureDao.getGeometryColumns().getContents();
				info.append("Feature Table");
				info.append("\nFeatures: ").append(featureDao.count());
				userTable = featureDao.getTable();
			} else {
				tileDao = geoPackage.getTileDao(table.getName());
				contents = tileDao.getTileMatrixSet().getContents();
				info.append("Tile Table");
				info.append("\nZoom Levels: ").append(
						tileDao.getTileMatrices().size());
				info.append("\nTiles: ").append(tileDao.count());
				userTable = tileDao.getTable();
			}

			SpatialReferenceSystem srs = contents.getSrs();

			info.append("\n\nSpatial Reference System:");
			addSrs(info, srs);

			info.append("\n\nContents:");
			info.append("\nTable Name: ").append(contents.getTableName());
			info.append("\nData Type: ").append(contents.getDataType());
			info.append("\nIdentifier: ").append(contents.getIdentifier());
			info.append("\nDescription: ").append(contents.getDescription());
			info.append("\nLast Change: ").append(contents.getLastChange());
			info.append("\nMin X: ").append(contents.getMinX());
			info.append("\nMin Y: ").append(contents.getMinY());
			info.append("\nMax X: ").append(contents.getMaxX());
			info.append("\nMax Y: ").append(contents.getMaxY());

			if (featureDao != null) {
				GeometryColumns geometryColumns = featureDao
						.getGeometryColumns();
				info.append("\n\nGeometry Columns:");
				info.append("\nTable Name: ").append(
						geometryColumns.getTableName());
				info.append("\nColumn Name: ").append(
						geometryColumns.getColumnName());
				info.append("\nGeometry Type Name: ").append(
						geometryColumns.getGeometryTypeName());
				info.append("\nZ: ").append(geometryColumns.getZ());
				info.append("\nM: ").append(geometryColumns.getM());
			}

			if (tileDao != null) {
				TileMatrixSet tileMatrixSet = tileDao.getTileMatrixSet();
				info.append("\n\nTile Matrix Set:");
				info.append("\nTable Name: ").append(
						tileMatrixSet.getTableName());
				info.append("\nMin X: ").append(tileMatrixSet.getMinX());
				info.append("\nMin Y: ").append(tileMatrixSet.getMinY());
				info.append("\nMax X: ").append(tileMatrixSet.getMaxX());
				info.append("\nMax Y: ").append(tileMatrixSet.getMaxY());

				info.append("\n\nTile Matrices:");
				for (TileMatrix tileMatrix : tileDao.getTileMatrices()) {
					info.append("\n\nTable Name: ").append(
							tileMatrix.getTableName());
					info.append("\nZoom Level: ").append(
							tileMatrix.getZoomLevel());
					info.append("\nTiles: ").append(
							tileDao.count(tileMatrix.getZoomLevel()));
					info.append("\nMatrix Width: ").append(
							tileMatrix.getMatrixWidth());
					info.append("\nMatrix Height: ").append(
							tileMatrix.getMatrixHeight());
					info.append("\nTile Width: ").append(
							tileMatrix.getTileWidth());
					info.append("\nTile Height: ").append(
							tileMatrix.getTileHeight());
					info.append("\nPixel X Size: ").append(
							tileMatrix.getPixelXSize());
					info.append("\nPixel Y Size: ").append(
							tileMatrix.getPixelYSize());
				}
			}

			info.append("\n\n").append(table.getName()).append(" columns:");
			for (UserColumn userColumn : userTable.getColumns()) {
				info.append("\n\nIndex: ").append(userColumn.getIndex());
				info.append("\nName: ").append(userColumn.getName());
				if (userColumn.getMax() != null) {
					info.append("\nMax: ").append(userColumn.getMax());
				}
				info.append("\nNot Null: ").append(userColumn.isNotNull());
				if (userColumn.getDefaultValue() != null) {
					info.append("\nDefault Value: ").append(
							userColumn.getDefaultValue());
				}
				if (userColumn.isPrimaryKey()) {
					info.append("\nPrimary Key: ").append(
							userColumn.isPrimaryKey());
				}
				info.append("\nType: ").append(userColumn.getTypeName());
			}

		} catch (GeoPackageException e) {
			info.append(e.getMessage());
		} finally {
			geoPackage.close();
		}
		AlertDialog viewDialog = new AlertDialog.Builder(getActivity())
				.setTitle(table.getDatabase() + " - " + table.getName())
				.setPositiveButton(getString(R.string.button_ok_label),

				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				}).setMessage(info.toString()).create();
		viewDialog.show();
	}

	/**
	 * Edit table information
	 * 
	 * @param table
	 */
	private void editTableOption(final GeoPackageTable table) {

		LayoutInflater inflater = LayoutInflater.from(getActivity());

		int editTableViewId;
		if (table.isFeature()) {
			editTableViewId = R.layout.edit_features;
		} else {
			editTableViewId = R.layout.edit_tiles;
		}
		View editTableView = inflater.inflate(editTableViewId, null);
		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
		dialog.setView(editTableView);

		final EditText identifierInput = (EditText) editTableView
				.findViewById(R.id.edit_contents_identifier_input);
		final EditText descriptionInput = (EditText) editTableView
				.findViewById(R.id.edit_contents_description_input);
		final EditText minYInput = (EditText) editTableView
				.findViewById(R.id.edit_contents_min_y_input);
		final EditText maxYInput = (EditText) editTableView
				.findViewById(R.id.edit_contents_max_y_input);
		final EditText minXInput = (EditText) editTableView
				.findViewById(R.id.edit_contents_min_x_input);
		final EditText maxXInput = (EditText) editTableView
				.findViewById(R.id.edit_contents_max_x_input);

		EditText tempMinYMatrixSetInput = null;
		EditText tempMaxYMatrixSetInput = null;
		EditText tempMinXMatrixSetInput = null;
		EditText tempMaxXMatrixSetInput = null;

		Spinner tempGeometryTypeSpinner = null;
		EditText tempZInput = null;
		EditText tempMInput = null;

		final GeoPackage geoPackage = manager.open(table.getDatabase());
		Contents tempContents = null;
		TileMatrixSet tempTileMatrixSet = null;
		GeometryColumns tempGeometryColumns = null;
		try {
			if (table.isTile()) {
				TileMatrixSetDao tileMatrixSetDao = geoPackage
						.getTileMatrixSetDao();
				tempTileMatrixSet = tileMatrixSetDao
						.queryForId(table.getName());
				tempContents = tempTileMatrixSet.getContents();

				tempMinYMatrixSetInput = (EditText) editTableView
						.findViewById(R.id.edit_tiles_min_y_input);
				tempMaxYMatrixSetInput = (EditText) editTableView
						.findViewById(R.id.edit_tiles_max_y_input);
				tempMinXMatrixSetInput = (EditText) editTableView
						.findViewById(R.id.edit_tiles_min_x_input);
				tempMaxXMatrixSetInput = (EditText) editTableView
						.findViewById(R.id.edit_tiles_max_x_input);

				tempMinYMatrixSetInput.setText(String.valueOf(tempTileMatrixSet
						.getMinY()));
				tempMaxYMatrixSetInput.setText(String.valueOf(tempTileMatrixSet
						.getMaxY()));
				tempMinXMatrixSetInput.setText(String.valueOf(tempTileMatrixSet
						.getMinX()));
				tempMaxXMatrixSetInput.setText(String.valueOf(tempTileMatrixSet
						.getMaxX()));
			} else {

				tempGeometryTypeSpinner = (Spinner) editTableView
						.findViewById(R.id.edit_features_geometry_type);
				tempZInput = (EditText) editTableView
						.findViewById(R.id.edit_features_z_input);
				tempMInput = (EditText) editTableView
						.findViewById(R.id.edit_features_m_input);

				tempZInput
						.setFilters(new InputFilter[] { new InputFilterMinMax(
								0, 2) });
				tempMInput
						.setFilters(new InputFilter[] { new InputFilterMinMax(
								0, 2) });

				GeometryColumnsDao geometryColumnsDao = geoPackage
						.getGeometryColumnsDao();
				tempGeometryColumns = geometryColumnsDao
						.queryForTableName(table.getName());
				tempContents = tempGeometryColumns.getContents();

				tempGeometryTypeSpinner.setSelection(tempGeometryColumns
						.getGeometryType().getCode());
				tempZInput.setText(String.valueOf(tempGeometryColumns.getZ()));
				tempMInput.setText(String.valueOf(tempGeometryColumns.getM()));
			}

			identifierInput.setText(tempContents.getIdentifier());
			descriptionInput.setText(tempContents.getDescription());
			if (tempContents.getMinY() != null) {
				minYInput.setText(tempContents.getMinY().toString());
			}
			if (tempContents.getMaxY() != null) {
				maxYInput.setText(tempContents.getMaxY().toString());
			}
			if (tempContents.getMinX() != null) {
				minXInput.setText(tempContents.getMinX().toString());
			}
			if (tempContents.getMaxX() != null) {
				maxXInput.setText(tempContents.getMaxX().toString());
			}

		} catch (Exception e) {
			geoPackage.close();
			GeoPackageUtils.showMessage(getActivity(),
					getString(R.string.geopackage_table_edit_label),
					e.getMessage());
			return;
		}

		final Contents contents = tempContents;
		final Spinner geometryTypeSpinner = tempGeometryTypeSpinner;
		final EditText zInput = tempZInput;
		final EditText mInput = tempMInput;
		final TileMatrixSet tileMatrixSet = tempTileMatrixSet;
		final GeometryColumns geometryColumns = tempGeometryColumns;
		final EditText minYMatrixSetInput = tempMinYMatrixSetInput;
		final EditText maxYMatrixSetInput = tempMaxYMatrixSetInput;
		final EditText minXMatrixSetInput = tempMinXMatrixSetInput;
		final EditText maxXMatrixSetInput = tempMaxXMatrixSetInput;

		dialog.setPositiveButton(getString(R.string.button_ok_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {

						try {

							String identifier = identifierInput.getText()
									.toString();
							String description = descriptionInput.getText()
									.toString();

							String minYString = minYInput.getText().toString();
							Double minY = minYString != null
									&& !minYString.isEmpty() ? Double
									.valueOf(minYString) : null;

							String maxYString = maxYInput.getText().toString();
							Double maxY = maxYString != null
									&& !maxYString.isEmpty() ? Double
									.valueOf(maxYString) : null;

							String minXString = minXInput.getText().toString();
							Double minX = minXString != null
									&& !minXString.isEmpty() ? Double
									.valueOf(minXString) : null;

							String maxXString = maxXInput.getText().toString();
							Double maxX = maxXString != null
									&& !maxXString.isEmpty() ? Double
									.valueOf(maxXString) : null;

							if (minY != null && maxY != null && minY > maxY) {
								throw new GeoPackageException(
										getString(R.string.edit_contents_min_y_label)
												+ " can not be larger than "
												+ getString(R.string.edit_contents_max_y_label));
							}

							if (minX != null && maxX != null && minX > maxX) {
								throw new GeoPackageException(
										getString(R.string.edit_contents_min_x_label)
												+ " can not be larger than "
												+ getString(R.string.edit_contents_max_x_label));
							}

							if (table.isTile()) {
								TileMatrixSetDao tileMatrixSetDao = geoPackage
										.getTileMatrixSetDao();

								String minYMatrixSetString = minYMatrixSetInput
										.getText().toString();
								Double minMatrixSetY = minYMatrixSetString != null
										&& !minYMatrixSetString.isEmpty() ? Double
										.valueOf(minYMatrixSetString) : null;

								String maxYMatrixSetString = maxYMatrixSetInput
										.getText().toString();
								Double maxMatrixSetY = maxYMatrixSetString != null
										&& !maxYMatrixSetString.isEmpty() ? Double
										.valueOf(maxYMatrixSetString) : null;

								String minXMatrixSetString = minXMatrixSetInput
										.getText().toString();
								Double minMatrixSetX = minXMatrixSetString != null
										&& !minXMatrixSetString.isEmpty() ? Double
										.valueOf(minXMatrixSetString) : null;

								String maxXMatrixSetString = maxXMatrixSetInput
										.getText().toString();
								Double maxMatrixSetX = maxXMatrixSetString != null
										&& !maxXMatrixSetString.isEmpty() ? Double
										.valueOf(maxXMatrixSetString) : null;

								if (minMatrixSetY == null
										|| maxMatrixSetY == null
										|| minMatrixSetX == null
										|| maxMatrixSetX == null) {
									throw new GeoPackageException(
											"Min and max bounds are required for Tiles");
								}
								tileMatrixSet.setMinY(minMatrixSetY);
								tileMatrixSet.setMaxY(maxMatrixSetY);
								tileMatrixSet.setMinX(minMatrixSetX);
								tileMatrixSet.setMaxX(maxMatrixSetX);

								tileMatrixSetDao.update(tileMatrixSet);
							} else {
								GeometryColumnsDao geometryColumnsDao = geoPackage
										.getGeometryColumnsDao();

								geometryColumns.setGeometryType(GeometryType
										.fromName(geometryTypeSpinner
												.getSelectedItem().toString()));
								geometryColumns.setZ(Byte.valueOf(zInput
										.getText().toString()));
								geometryColumns.setM(Byte.valueOf(mInput
										.getText().toString()));

								geometryColumnsDao.update(geometryColumns);
							}

							ContentsDao contentsDao = geoPackage
									.getContentsDao();
							contents.setIdentifier(identifier);
							contents.setDescription(description);
							contents.setMinY(minY);
							contents.setMaxY(maxY);
							contents.setMinX(minX);
							contents.setMaxX(maxX);
							contents.setLastChange(new Date());
							contentsDao.update(contents);

						} catch (Exception e) {
							GeoPackageUtils
									.showMessage(
											getActivity(),
											getString(R.string.geopackage_table_edit_label),
											e.getMessage());
						} finally {
							geoPackage.close();
							update();
						}
					}
				}).setNegativeButton(getString(R.string.button_cancel_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {
						geoPackage.close();
						dialog.cancel();
					}
				});
		dialog.show();

	}

	/**
	 * Delete table alert option
	 * 
	 * @param table
	 */
	private void deleteTableOption(final GeoPackageTable table) {
		AlertDialog deleteDialog = new AlertDialog.Builder(getActivity())
				.setTitle(getString(R.string.geopackage_table_delete_label))
				.setMessage(
						getString(R.string.geopackage_table_delete_label) + " "
								+ table.getDatabase() + " - " + table.getName()
								+ "?")
				.setPositiveButton(
						getString(R.string.geopackage_table_delete_label),

						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int which) {

								GeoPackage geoPackage = manager.open(table
										.getDatabase());
								try {
									geoPackage.deleteTable(table.getName());
									active.removeTable(table);
									update();
								} catch (Exception e) {
									GeoPackageUtils.showMessage(getActivity(),
											"Delete " + table.getDatabase()
													+ " " + table.getName()
													+ " Table", e.getMessage());
								} finally {
									geoPackage.close();
								}
							}
						})

				.setNegativeButton(getString(R.string.button_cancel_label),
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int which) {
								dialog.dismiss();
							}
						}).create();
		deleteDialog.show();
	}

	/**
	 * Load tiles table alert option
	 * 
	 * @param table
	 */
	private void loadTilesTableOption(final GeoPackageTable table) {

		LayoutInflater inflater = LayoutInflater.from(getActivity());
		View loadTilesView = inflater.inflate(R.layout.load_tiles, null);
		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
		dialog.setView(loadTilesView);

		final EditText urlInput = (EditText) loadTilesView
				.findViewById(R.id.load_tiles_url_input);
		final Button preloadedUrlsButton = (Button) loadTilesView
				.findViewById(R.id.load_tiles_preloaded);
		final EditText minZoomInput = (EditText) loadTilesView
				.findViewById(R.id.load_tiles_min_zoom_input);
		final EditText maxZoomInput = (EditText) loadTilesView
				.findViewById(R.id.load_tiles_max_zoom_input);
		final Spinner compressFormatInput = (Spinner) loadTilesView
				.findViewById(R.id.load_tiles_compress_format);
		final EditText compressQualityInput = (EditText) loadTilesView
				.findViewById(R.id.load_tiles_compress_quality);
		final RadioButton googleTilesRadioButton = (RadioButton) loadTilesView
				.findViewById(R.id.load_tiles_type_google_radio_button);
		final EditText minLatInput = (EditText) loadTilesView
				.findViewById(R.id.bounding_box_min_latitude_input);
		final EditText maxLatInput = (EditText) loadTilesView
				.findViewById(R.id.bounding_box_max_latitude_input);
		final EditText minLonInput = (EditText) loadTilesView
				.findViewById(R.id.bounding_box_min_longitude_input);
		final EditText maxLonInput = (EditText) loadTilesView
				.findViewById(R.id.bounding_box_max_longitude_input);
		final Button preloadedLocationsButton = (Button) loadTilesView
				.findViewById(R.id.bounding_box_preloaded);

		GeoPackageUtils
				.prepareBoundingBoxInputs(getActivity(), minLatInput,
						maxLatInput, minLonInput, maxLonInput,
						preloadedLocationsButton);

		GeoPackageUtils.prepareTileLoadInputs(getActivity(), minZoomInput,
				maxZoomInput, preloadedUrlsButton, null, urlInput,
				compressFormatInput, compressQualityInput);

		dialog.setPositiveButton(
				getString(R.string.geopackage_table_tiles_load_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {

						try {

							String tileUrl = urlInput.getText().toString();
							int minZoom = Integer.valueOf(minZoomInput
									.getText().toString());
							int maxZoom = Integer.valueOf(maxZoomInput
									.getText().toString());
							double minLat = Double.valueOf(minLatInput
									.getText().toString());
							double maxLat = Double.valueOf(maxLatInput
									.getText().toString());
							double minLon = Double.valueOf(minLonInput
									.getText().toString());
							double maxLon = Double.valueOf(maxLonInput
									.getText().toString());

							if (minLat > maxLat) {
								throw new GeoPackageException(
										getString(R.string.bounding_box_min_latitude_label)
												+ " can not be larger than "
												+ getString(R.string.bounding_box_max_latitude_label));
							}

							if (minLon > maxLon) {
								throw new GeoPackageException(
										getString(R.string.bounding_box_min_longitude_label)
												+ " can not be larger than "
												+ getString(R.string.bounding_box_max_longitude_label));
							}

							CompressFormat compressFormat = null;
							Integer compressQuality = null;
							if (compressFormatInput.getSelectedItemPosition() > 0) {
								compressFormat = CompressFormat
										.valueOf(compressFormatInput
												.getSelectedItem().toString());
								compressQuality = Integer
										.valueOf(compressQualityInput.getText()
												.toString());
							}

							boolean googleTiles = googleTilesRadioButton
									.isChecked();

							BoundingBox boundingBox = new BoundingBox(minLon,
									maxLon, minLat, maxLat);

							// Load tiles
							LoadTilesTask.loadTiles(getActivity(),
									GeoPackageManagerFragment.this, active,
									table.getDatabase(), table.getName(),
									tileUrl, minZoom, maxZoom, compressFormat,
									compressQuality, googleTiles, boundingBox);
						} catch (Exception e) {
							GeoPackageUtils
									.showMessage(
											getActivity(),
											getString(R.string.geopackage_table_tiles_load_label),
											e.getMessage());
						}
					}
				}).setNegativeButton(getString(R.string.button_cancel_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
					}
				});
		dialog.show();
	}

	/**
	 * Handle manager menu clicks
	 * 
	 * @param item
	 * @return
	 */
	public boolean handleMenuClick(MenuItem item) {
		boolean handled = true;

		switch (item.getItemId()) {
		case R.id.import_geopackage_url:
			importGeopackageFromUrl();
			break;
		case R.id.import_geopackage_file:
			importGeopackageFromFile();
			break;
		case R.id.create_geopackage:
			createGeoPackage();
			break;
		case R.id.clear_selected_tables:
			active.removeAll();
			update();
			break;
		default:
			handled = false;
			break;
		}

		return handled;
	}

	/**
	 * Import a GeoPackage from a URL
	 */
	private void importGeopackageFromUrl() {

		LayoutInflater inflater = LayoutInflater.from(getActivity());
		View importUrlView = inflater.inflate(R.layout.import_url, null);
		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
		dialog.setView(importUrlView);

		final EditText nameInput = (EditText) importUrlView
				.findViewById(R.id.import_url_name_input);
		final EditText urlInput = (EditText) importUrlView
				.findViewById(R.id.import_url_input);
		final Button button = (Button) importUrlView
				.findViewById(R.id.import_url_preloaded);
		button.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				ArrayAdapter<String> adapter = new ArrayAdapter<String>(
						getActivity(), android.R.layout.select_dialog_item);
				adapter.addAll(getResources().getStringArray(
						R.array.preloaded_geopackage_url_labels));
				AlertDialog.Builder builder = new AlertDialog.Builder(
						getActivity());
				builder.setTitle(getString(R.string.import_url_preloaded_label));
				builder.setAdapter(adapter,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int item) {
								if (item >= 0) {
									String[] urls = getResources()
											.getStringArray(
													R.array.preloaded_geopackage_urls);
									String[] names = getResources()
											.getStringArray(
													R.array.preloaded_geopackage_url_names);
									nameInput.setText(names[item]);
									urlInput.setText(urls[item]);
								}
							}
						});

				AlertDialog alert = builder.create();
				alert.show();
			}
		});

		dialog.setPositiveButton(getString(R.string.geopackage_import_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {

						String database = nameInput.getText().toString();
						String url = urlInput.getText().toString();

						DownloadTask downloadTask = new DownloadTask(database,
								url);

						progressDialog = createDownloadProgressDialog(database,
								url, downloadTask, null);
						progressDialog.setIndeterminate(true);

						downloadTask.execute(database, url);
					}
				}).setNegativeButton(getString(R.string.button_cancel_label),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
					}
				});
		dialog.show();

	}

	/**
	 * Create a download progress dialog
	 * 
	 * @param database
	 * @param url
	 * @param downloadTask
	 * @param suffix
	 * @return
	 */
	private ProgressDialog createDownloadProgressDialog(String database,
			String url, final DownloadTask downloadTask, String suffix) {
		ProgressDialog dialog = new ProgressDialog(getActivity());
		dialog.setMessage(getString(R.string.geopackage_import_label) + " "
				+ database + "\n\n" + url + (suffix != null ? suffix : ""));
		dialog.setCancelable(false);
		dialog.setButton(ProgressDialog.BUTTON_NEGATIVE,
				getString(R.string.button_cancel_label),
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						downloadTask.cancel(true);
					}
				});
		return dialog;
	}

	/**
	 * Download a GeoPackage from a URL in the background
	 */
	private class DownloadTask extends AsyncTask<String, Integer, String>
			implements GeoPackageProgress {

		private Integer max = null;
		private int progress = 0;
		private final String database;
		private final String url;
		private PowerManager.WakeLock wakeLock;

		/**
		 * Constructor
		 * 
		 * @param database
		 * @param url
		 */
		public DownloadTask(String database, String url) {
			this.database = database;
			this.url = url;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void setMax(int max) {
			this.max = max;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void addProgress(int progress) {
			this.progress += progress;
			if (max != null) {
				int total = (int) (this.progress / ((double) max) * 100);
				publishProgress(total);
			}
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean isActive() {
			return !isCancelled();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean cleanupOnCancel() {
			return true;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			progressDialog.show();
			PowerManager pm = (PowerManager) getActivity().getSystemService(
					Context.POWER_SERVICE);
			wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
					getClass().getName());
			wakeLock.acquire();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void onProgressUpdate(Integer... progress) {
			super.onProgressUpdate(progress);

			// If the indeterminate progress dialog is still showing, swap to a
			// determinate horizontal bar
			if (progressDialog.isIndeterminate()) {

				String messageSuffix = "\n\n"
						+ GeoPackageIOUtils.formatBytes(max);

				ProgressDialog newProgressDialog = createDownloadProgressDialog(
						database, url, this, messageSuffix);
				newProgressDialog
						.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
				newProgressDialog.setIndeterminate(false);
				newProgressDialog.setMax(100);

				newProgressDialog.show();
				progressDialog.dismiss();
				progressDialog = newProgressDialog;
			}

			// Set the progress
			progressDialog.setProgress(progress[0]);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void onCancelled(String result) {
			wakeLock.release();
			progressDialog.dismiss();
			update();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void onPostExecute(String result) {
			wakeLock.release();
			progressDialog.dismiss();
			if (result != null) {
				GeoPackageUtils.showMessage(getActivity(),
						getString(R.string.geopackage_import_label), result);
			}
			update();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected String doInBackground(String... params) {
			try {
				URL theUrl = new URL(url);
				if (!manager.importGeoPackage(database, theUrl, this)) {
					return "Failed to import GeoPackage '" + database
							+ "' at url '" + url + "'";
				}
			} catch (final Exception e) {
				return e.toString();
			}
			return null;
		}

	}

	/**
	 * Import a GeoPackage from a file
	 */
	private void importGeopackageFromFile() {

		try {
			Intent chooseFile = new Intent(Intent.ACTION_GET_CONTENT);
			chooseFile.setType("*/*");
			Intent intent = Intent.createChooser(chooseFile,
					"Choose a GeoPackage file");
			startActivityForResult(intent, MainActivity.ACTIVITY_CHOOSE_FILE);
		} catch (Exception e) {
			// eat
		}

	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		boolean handled = true;

		switch (requestCode) {
		case MainActivity.ACTIVITY_CHOOSE_FILE:
			if (resultCode == Activity.RESULT_OK) {
				importFile(data);
			}
			break;

		case MainActivity.ACTIVITY_SHARE_FILE:
			// Delete any cached database files
			File databaseCache = getDatabaseCacheDirectory();
			if (databaseCache.exists()) {
				File[] cacheFiles = databaseCache.listFiles();
				if (cacheFiles != null) {
					for (File cacheFile : cacheFiles) {
						cacheFile.delete();
					}
				}
				databaseCache.delete();
			}
			break;

		default:
			handled = false;
		}

		if (!handled) {
			super.onActivityResult(requestCode, resultCode, data);
		}
	}

	/**
	 * Import the GeoPackage file selected
	 * 
	 * @param data
	 */
	private void importFile(Intent data) {

		// Get the Uri
		final Uri uri = data.getData();

		final ContentResolver resolver = getActivity().getContentResolver();

		// Try to get the file path
		final String path = FileUtils.getPath(getActivity(), uri);

		// Try to get the GeoPackage name
		String name = null;
		if (path != null) {
			name = new File(path).getName();
		} else {
			name = getDisplayName(resolver, uri);
		}

		// Remove the extension
		if (name != null) {
			int extensionIndex = name.lastIndexOf(".");
			if (extensionIndex > -1) {
				name = name.substring(0, extensionIndex);
			}
		}

		LayoutInflater inflater = LayoutInflater.from(getActivity());
		View importFileView = inflater.inflate(R.layout.import_file, null);
		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity());
		dialog.setView(importFileView);

		final EditText nameInput = (EditText) importFileView
				.findViewById(R.id.import_file_name_input);
		final RadioButton copyRadioButton = (RadioButton) importFileView
				.findViewById(R.id.import_file_copy_radio_button);
		final RadioButton externalRadioButton = (RadioButton) importFileView
				.findViewById(R.id.import_file_external_radio_button);

		// Set the default name
		if (name != null) {
			nameInput.setText(name);
		}

		// If no file path could be found, disable the external link option
		if (path == null) {
			externalRadioButton.setEnabled(false);
		}

		dialog.setTitle(getString(R.string.geopackage_import_label))
				.setPositiveButton(getString(R.string.button_ok_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {

								String value = nameInput.getText().toString();
								if (value != null && !value.isEmpty()) {

									boolean copy = copyRadioButton.isChecked();

									try {
										InputStream stream = resolver
												.openInputStream(uri);
										boolean imported = false;

										// Import the GeoPackage by copying the
										// file
										if (copy) {
											imported = manager
													.importGeoPackage(value,
															stream);
										} else {
											// Import the GeoPackage by linking
											// to the file
											imported = manager
													.importGeoPackageAsExternalLink(
															path, value);
										}
										if (imported) {
											update();
										} else {
											try {
												getActivity().runOnUiThread(
														new Runnable() {
															@Override
															public void run() {
																GeoPackageUtils
																		.showMessage(
																				getActivity(),
																				"URL Import",
																				"Failed to import Uri: "
																						+ uri.getPath());
															}
														});
											} catch (Exception e2) {
												// eat
											}
										}
									} catch (final Exception e) {
										try {
											getActivity().runOnUiThread(
													new Runnable() {
														@Override
														public void run() {
															GeoPackageUtils
																	.showMessage(
																			getActivity(),
																			"File Import",
																			"Uri: "
																					+ uri.getPath()
																					+ ", "
																					+ e.getMessage());
														}
													});
										} catch (Exception e2) {
											// eat
										}
									}
								}
							}
						})
				.setNegativeButton(getString(R.string.button_cancel_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								dialog.cancel();
							}
						});

		dialog.show();
	}

	/**
	 * Get display name from the uri
	 * 
	 * @param resolver
	 * @param uri
	 * @return
	 */
	@TargetApi(Build.VERSION_CODES.KITKAT)
	private String getDisplayName(ContentResolver resolver, Uri uri) {

		String name = null;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			Cursor nameCursor = resolver.query(uri, null, null, null, null);
			try {
				if (nameCursor.getCount() > 0) {
					int displayNameIndex = nameCursor
							.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
					if (displayNameIndex >= 0 && nameCursor.moveToFirst()) {
						name = nameCursor.getString(displayNameIndex);
					}
				}
			} finally {
				nameCursor.close();
			}
		}

		return name;
	}

	/**
	 * Create a new GeoPackage
	 */
	private void createGeoPackage() {

		final EditText input = new EditText(getActivity());

		AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity())
				.setTitle(getString(R.string.geopackage_create_label))
				.setView(input)
				.setPositiveButton(getString(R.string.button_ok_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								String value = input.getText().toString();
								if (value != null && !value.isEmpty()) {
									try {
										if (manager.create(value)) {
											update();
										} else {
											GeoPackageUtils
													.showMessage(
															getActivity(),
															getString(R.string.geopackage_create_label),
															"Failed to create GeoPackage: "
																	+ value);
										}
									} catch (Exception e) {
										GeoPackageUtils.showMessage(
												getActivity(), "Create "
														+ value, e.getMessage());
									}
								}
							}
						})
				.setNegativeButton(getString(R.string.button_cancel_label),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								dialog.cancel();
							}
						});

		dialog.show();
	}

	/**
	 * Expandable list adapter
	 */
	public class GeoPackageListAdapter extends BaseExpandableListAdapter {

		@Override
		public int getGroupCount() {
			return databases.size();
		}

		@Override
		public int getChildrenCount(int i) {
			return databaseTables.get(i).size();
		}

		@Override
		public Object getGroup(int i) {
			return databases.get(i);
		}

		@Override
		public Object getChild(int i, int j) {
			return databaseTables.get(i).get(j);
		}

		@Override
		public long getGroupId(int i) {
			return i;
		}

		@Override
		public long getChildId(int i, int j) {
			return j;
		}

		@Override
		public boolean hasStableIds() {
			return false;
		}

		@Override
		public View getGroupView(int i, boolean isExpanded, View view,
				ViewGroup viewGroup) {
			if (view == null) {
				view = inflater.inflate(R.layout.manager_group, null);
			}

			TextView geoPackageName = (TextView) view
					.findViewById(R.id.manager_group_name);
			geoPackageName.setText(databases.get(i));

			return view;
		}

		@Override
		public View getChildView(int i, int j, boolean b, View view,
				ViewGroup viewGroup) {
			if (view == null) {
				view = inflater.inflate(R.layout.manager_child, null);
			}

			final GeoPackageTable table = databaseTables.get(i).get(j);

			CheckBox checkBox = (CheckBox) view
					.findViewById(R.id.manager_child_checkbox);
			ImageView imageView = (ImageView) view
					.findViewById(R.id.manager_child_image);
			TextView tableName = (TextView) view
					.findViewById(R.id.manager_child_name);
			TextView count = (TextView) view
					.findViewById(R.id.manager_child_count);

			checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

				@Override
				public void onCheckedChanged(CompoundButton buttonView,
						boolean isChecked) {
					if (table.isActive() != isChecked) {
						table.setActive(isChecked);
						if (isChecked) {
							active.addTable(table);
						} else {
							active.removeTable(table);
						}
					}
				}
			});
			tableName.setOnLongClickListener(new View.OnLongClickListener() {

				@Override
				public boolean onLongClick(View v) {
					GeoPackageManagerFragment.this.tableOptions(table);
					return true;
				}
			});

			checkBox.setChecked(table.isActive());
			if (table.isFeature()) {
				GeometryType geometryType = table.getGeometryType();
				int drawableId = R.drawable.ic_geometry;
				if (geometryType != null) {

					switch (geometryType) {

					case POINT:
					case MULTIPOINT:
						drawableId = R.drawable.ic_point;
						break;

					case LINESTRING:
					case MULTILINESTRING:
					case CURVE:
					case COMPOUNDCURVE:
					case CIRCULARSTRING:
					case MULTICURVE:
						drawableId = R.drawable.ic_linestring;
						break;

					case POLYGON:
					case SURFACE:
					case CURVEPOLYGON:
					case TRIANGLE:
					case POLYHEDRALSURFACE:
					case TIN:
					case MULTIPOLYGON:
					case MULTISURFACE:
						drawableId = R.drawable.ic_polygon;
						break;

					case GEOMETRY:
					case GEOMETRYCOLLECTION:
						drawableId = R.drawable.ic_geometry;
						break;
					}
				}
				imageView.setImageDrawable(getResources().getDrawable(
						drawableId));
			} else {
				imageView.setImageDrawable(getResources().getDrawable(
						R.drawable.ic_tiles));
			}

			tableName.setText(table.getName());
			count.setText("(" + String.valueOf(table.getCount()) + ")");

			return view;
		}

		@Override
		public boolean isChildSelectable(int i, int j) {
			return true;
		}

	}

}
