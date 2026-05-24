get_filename_component(_pocket_mocap_eigen_config_dir "${CMAKE_CURRENT_LIST_FILE}" DIRECTORY)
get_filename_component(_pocket_mocap_third_party_dir "${_pocket_mocap_eigen_config_dir}" DIRECTORY)
set(EIGEN3_INCLUDE_DIR "${_pocket_mocap_third_party_dir}/eigen")
set(Eigen3_VERSION "3.4.0")

if (NOT TARGET Eigen3::Eigen)
    add_library(Eigen3::Eigen INTERFACE IMPORTED)
    set_target_properties(
        Eigen3::Eigen
        PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${EIGEN3_INCLUDE_DIR}"
    )
endif()

set(Eigen3_FOUND TRUE)
