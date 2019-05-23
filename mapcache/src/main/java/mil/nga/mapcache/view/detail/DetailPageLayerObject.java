package mil.nga.mapcache.view.detail;

import android.text.TextUtils;

import mil.nga.geopackage.GeoPackage;
import mil.nga.mapcache.R;
import mil.nga.mapcache.data.GeoPackageFeatureTable;
import mil.nga.mapcache.data.GeoPackageTable;

/**
 * Holds data for a single layer belonging to a GeoPackage.  When the GeoPackage Detail View's recycler
 * view is created, it will make a row containing these objects in order to display the layer names,
 * layer type icon, and active/inactive switch
 */
public class DetailPageLayerObject {
    // Icon to show if it's a feature layer or tile layer
    private int iconType;
    // The name of this layer
    private String name;
    // checked value to coordinate with the active/inactive switch (turns the layer on or off)
    private boolean checked;
    // GeoPackage name
    private String geoPackageName;
    // GeoPackageTable object
    private GeoPackageTable table;
    // Description
    private String description;

    /**
     * Constructor
     * @param layerName
     */
    public DetailPageLayerObject(String layerName, String geoPackageName, boolean checked, GeoPackageTable table){
        this.name = layerName;
        this.geoPackageName = geoPackageName;
        this.checked = checked;
        this.table = table;
        this.description = table.getDescription();
        if(table instanceof GeoPackageFeatureTable){
            this.iconType = R.drawable.material_feature;
        } else{
            this.iconType = R.drawable.material_tile;
        }
    }

    /**
     * Getters and setters
     */

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getIconType() {
        return iconType;
    }

    public void setIconType(int iconType) {
        this.iconType = iconType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public String getGeoPackageName() {
        return geoPackageName;
    }

    public void setGeoPackageName(String geoPackageName) {
        this.geoPackageName = geoPackageName;
    }

    public GeoPackageTable getTable() {
        return table;
    }

    public void setTable(GeoPackageTable table) {
        this.table = table;
    }
}
