#include <jni.h>

#include <camera/NdkCameraManager.h>
#include <dlfcn.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#if !defined(__ANDROID_API__) || __ANDROID_API__ != 23
#error "PhotonEx2 Camera-NDK bridge must remain compiled at the Android API-23 baseline"
#endif

namespace {
constexpr std::int32_t kCameraNdkMinimumApi = 24;
constexpr std::size_t kMaxIds = 64U;
constexpr std::size_t kMaxIdBytes = 256U;

class DynamicLibrary final {
 public:
  explicit DynamicLibrary(const char* name) noexcept : handle_(dlopen(name, RTLD_NOW | RTLD_LOCAL)) {}
  ~DynamicLibrary() {
    if (handle_ != nullptr) dlclose(handle_);
  }

  DynamicLibrary(const DynamicLibrary&) = delete;
  DynamicLibrary& operator=(const DynamicLibrary&) = delete;

  template <typename Function>
  [[nodiscard]] Function symbol(const char* name) const noexcept {
    if (handle_ == nullptr) return nullptr;
    return reinterpret_cast<Function>(dlsym(handle_, name));
  }

  [[nodiscard]] bool available() const noexcept { return handle_ != nullptr; }

 private:
  void* handle_ = nullptr;
};

struct Functions final {
  using CreateManager = ACameraManager* (*)();
  using DeleteManager = void (*)(ACameraManager*);
  using GetCameraIdList = camera_status_t (*)(ACameraManager*, ACameraIdList**);
  using DeleteCameraIdList = void (*)(ACameraIdList*);

  CreateManager create_manager = nullptr;
  DeleteManager delete_manager = nullptr;
  GetCameraIdList get_camera_id_list = nullptr;
  DeleteCameraIdList delete_camera_id_list = nullptr;

  [[nodiscard]] bool complete() const noexcept {
    return create_manager != nullptr && delete_manager != nullptr &&
           get_camera_id_list != nullptr && delete_camera_id_list != nullptr;
  }
};

Functions LoadFunctions(const DynamicLibrary& library) noexcept {
  Functions functions;
  functions.create_manager = library.symbol<Functions::CreateManager>("ACameraManager_create");
  functions.delete_manager = library.symbol<Functions::DeleteManager>("ACameraManager_delete");
  functions.get_camera_id_list =
      library.symbol<Functions::GetCameraIdList>("ACameraManager_getCameraIdList");
  functions.delete_camera_id_list =
      library.symbol<Functions::DeleteCameraIdList>("ACameraManager_deleteCameraIdList");
  return functions;
}

std::vector<std::string> CollectIds(std::int32_t android_api) noexcept {
  std::vector<std::string> output;
  if (android_api < kCameraNdkMinimumApi) return output;

  DynamicLibrary library("libcamera2ndk.so");
  if (!library.available()) return output;
  const Functions functions = LoadFunctions(library);
  if (!functions.complete()) return output;

  ACameraManager* manager = functions.create_manager();
  if (manager == nullptr) return output;

  ACameraIdList* list = nullptr;
  const camera_status_t status = functions.get_camera_id_list(manager, &list);
  if (status != ACAMERA_OK || list == nullptr) {
    functions.delete_manager(manager);
    return output;
  }

  if (list->numCameras >= 0 && static_cast<std::size_t>(list->numCameras) <= kMaxIds &&
      (list->numCameras == 0 || list->cameraIds != nullptr)) {
    output.reserve(static_cast<std::size_t>(list->numCameras));
    for (std::int32_t index = 0; index < list->numCameras; ++index) {
      const char* raw = list->cameraIds[index];
      if (raw == nullptr) continue;
      const std::size_t length = strnlen(raw, kMaxIdBytes + 1U);
      if (length == 0U || length > kMaxIdBytes) continue;
      output.emplace_back(raw, length);
    }
  }

  functions.delete_camera_id_list(list);
  functions.delete_manager(manager);
  return output;
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_sahidcode404_photonex2_camera_NdkCameraIdSource_nativeCameraIds(
    JNIEnv* environment,
    jobject /* receiver */,
    jint android_api) {
  const std::vector<std::string> ids = CollectIds(static_cast<std::int32_t>(android_api));
  jclass string_class = environment->FindClass("java/lang/String");
  if (string_class == nullptr || environment->ExceptionCheck() == JNI_TRUE) return nullptr;

  jobjectArray result = environment->NewObjectArray(
      static_cast<jsize>(ids.size()),
      string_class,
      nullptr);
  if (result == nullptr) return nullptr;

  for (std::size_t index = 0U; index < ids.size(); ++index) {
    jstring value = environment->NewStringUTF(ids[index].c_str());
    if (value == nullptr || environment->ExceptionCheck() == JNI_TRUE) return nullptr;
    environment->SetObjectArrayElement(result, static_cast<jsize>(index), value);
    environment->DeleteLocalRef(value);
    if (environment->ExceptionCheck() == JNI_TRUE) return nullptr;
  }
  return result;
}
